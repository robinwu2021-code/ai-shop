package ai.neargo.shop.trade.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.shop.spi.trade.IndustryOrderPort.Result;
import ai.neargo.shop.spi.trade.PostpayReceiptPort;
import ai.neargo.shop.spi.trade.PostpayReceiptPort.Confirm;
import ai.neargo.shop.spi.trade.PostpayReceiptPort.Receipt;
import ai.neargo.shop.spi.trade.PostpayReceiptPort.Root;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Scope;
import ai.neargo.shop.trade.entity.OrdIndustryOrderSource;
import ai.neargo.shop.trade.entity.OrdOfflineRecord;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.entity.OrdPostpayReceipt;
import ai.neargo.shop.trade.entity.OrdStatusLog;
import ai.neargo.shop.trade.mapper.PostpayReceiptMapper;
import ai.neargo.shop.trade.order.mapper.OrderStatusLogMapper;
import ai.neargo.shop.trade.service.IndustryOrderService;
import ai.neargo.shop.trade.service.PostpayReceiptService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 行业后付单的人工实收确认（TDD-餐饮柜台后付结账 · ADR-059）。
 *
 * <p>顺序有讲究：<b>先锁核心单，再查有没有已确认的实收</b>。两个并发确认同一张结算单时，第二个会在锁上等第一个提交，
 * 醒来后看到已有实收就原样返回，而不是把「状态已是 PAID」误报成账单被改。
 *
 * <p>金额与版本都由核心自己按门店读订单得出，不信调用方：调用方传来的只是「冻结时看到的」，用来比对有没有被改过。
 */
@Service
public class PostpayReceiptServiceImpl implements PostpayReceiptService {
    static final int MAX_ROOTS = 50;
    static final int MAX_RECORDS = 20;
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_.:-]{1,64}");
    private static final String CASH = "CASH";

    private final PostpayReceiptMapper receipts;
    private final IndustryOrderService industryOrders;
    private final OrderStatusLogMapper logs;
    private final OutboxEventBus events;
    private final Clock clock;
    private final TransactionTemplate tx;

    @Autowired
    public PostpayReceiptServiceImpl(PostpayReceiptMapper receipts, IndustryOrderService industryOrders,
            OrderStatusLogMapper logs, OutboxEventBus events, PlatformTransactionManager manager) {
        this(receipts, industryOrders, logs, events, manager, Clock.systemUTC());
    }

    PostpayReceiptServiceImpl(PostpayReceiptMapper receipts, IndustryOrderService industryOrders,
            OrderStatusLogMapper logs, OutboxEventBus events, PlatformTransactionManager manager, Clock clock) {
        this.receipts = receipts;
        this.industryOrders = industryOrders;
        this.logs = logs;
        this.events = events;
        this.clock = clock;
        this.tx = new TransactionTemplate(manager);
    }

    /**
     * 读核心应收（{@link IndustryOrderService#getForStore}）规定不能在事务里调用，所以分两段：
     * 事务外读应收与版本并记下订单行版本；事务内加锁后只比对订单行版本没变（账单此时已被结算单冻结，
     * 推 PAID 的条件更新也带版本守卫）。
     */
    @Override
    public Receipt confirm(Confirm c) {
        validate(c);
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("postpay receipt confirmation must not run inside a caller transaction");
        }
        // 门店归属在下面逐项显式核对；不让请求上下文里的数据权限再改写这些查询
        return DataScopeContext.executeWithoutScope(() -> {
            String payload = payloadHash(c);
            OrdPostpayReceipt existing = receipts.findByBiz(c.entityNo(), c.storeNo(), c.bizNo());
            if (existing != null) {
                return replay(existing, payload);
            }
            Map<String, Long> versions = readCurrent(c);
            return tx.execute(status -> confirmLocked(c, payload, versions));
        });
    }

    private Receipt replay(OrdPostpayReceipt existing, String payload) {
        if (!existing.getPayloadHash().equals(payload)) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        return receipt(existing, receipts.rootOrders(existing.getReceiptNo()), true);
    }

    /** 事务外：每张核心单的当前应收与版本必须与命令一致；返回读到时的订单行版本。 */
    private Map<String, Long> readCurrent(Confirm c) {
        Scope scope = new Scope(c.tenantNo(), c.entityNo(), c.storeNo());
        Map<String, Long> versions = new HashMap<>();
        for (Root root : c.roots()) {
            Result current = industryOrders.getForStore(scope, c.actorNo(), root.orderNo());
            if (current == null || PostpayReceiptPort.dueMinor(current) != root.dueMinor()
                    || !PostpayReceiptPort.coreVersion(current).equals(root.coreVersion())) {
                throw BizException.of(ErrorCode.POSTPAY_RECEIPT_ORDER_CHANGED);
            }
        }
        List<String> orderNos = c.roots().stream().map(Root::orderNo).sorted().toList();
        for (OrdOrder o : receipts.lockOrders(orderNos)) {
            versions.put(o.getOrderNo(), o.getVersion());
        }
        return versions;
    }

    private Receipt confirmLocked(Confirm c, String payload, Map<String, Long> versionsRead) {
        List<String> orderNos = c.roots().stream().map(Root::orderNo).sorted().toList();

        // 1. 先锁核心单
        List<OrdOrder> orders = receipts.lockOrders(orderNos);

        // 2. 再看这张结算单是否已经确认过（并发的第二个确认在这里看到第一个的结果）
        OrdPostpayReceipt existing = receipts.findByBiz(c.entityNo(), c.storeNo(), c.bizNo());
        if (existing != null) {
            return replay(existing, payload);
        }

        // 3. 核对每张核心单：门店、后付、待支付、币种、当前应收与版本
        if (orders.size() != orderNos.size()) {
            throw BizException.of(ErrorCode.POSTPAY_RECEIPT_ORDER_CHANGED);
        }
        Map<String, OrdIndustryOrderSource> sources = new HashMap<>();
        receipts.sources(orderNos).forEach(row -> sources.put(row.getOrderNo(), row));
        Map<String, OrdOrder> byNo = new HashMap<>();
        orders.forEach(o -> byNo.put(o.getOrderNo(), o));
        long due = 0;
        for (Root root : c.roots()) {
            OrdOrder order = byNo.get(root.orderNo());
            OrdIndustryOrderSource source = sources.get(root.orderNo());
            if (order == null || source == null || !"POSTPAY".equals(source.getOrderMode())
                    || !c.entityNo().equals(source.getEntityNo()) || !c.storeNo().equals(source.getStoreNo())) {
                throw BizException.of(ErrorCode.FORBIDDEN);
            }
            if (!"WAIT_PAY".equals(order.getStatus()) || !c.currency().equals(order.getCurrency())) {
                throw BizException.of(ErrorCode.POSTPAY_RECEIPT_ORDER_CHANGED);
            }
            // 应收与版本已在事务外按核心读口核过；这里只确认读完之后订单行没动过
            if (!Objects.equals(versionsRead.get(root.orderNo()), order.getVersion())) {
                throw BizException.of(ErrorCode.POSTPAY_RECEIPT_ORDER_CHANGED);
            }
            due = Math.addExact(due, root.dueMinor());
        }
        if (due <= 0) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }

        // 4. 核对外部记账：存在、同门店、结算单号一致、未作废、币种一致、没被别的实收用过
        List<String> recordNos = c.offlineRecordNos().stream().sorted().toList();
        List<OrdOfflineRecord> records = receipts.lockRecords(recordNos);
        if (records.size() != recordNos.size() || !receipts.usedRecords(recordNos).isEmpty()) {
            throw BizException.of(ErrorCode.POSTPAY_RECEIPT_RECORD_INVALID);
        }
        long received = 0;
        long cash = 0;
        LocalDateTime receivedAt = null;
        for (OrdOfflineRecord r : records) {
            if (!c.entityNo().equals(r.getEntityNo()) || !c.storeNo().equals(r.getStoreNo())
                    || !c.bizNo().equals(r.getBizNo()) || !OrdOfflineRecord.RECORDED.equals(r.getStatus())
                    || !c.currency().equals(r.getCurrency()) || r.getAmountMinor() == null
                    || r.getAmountMinor() <= 0) {
                throw BizException.of(ErrorCode.POSTPAY_RECEIPT_RECORD_INVALID);
            }
            received = Math.addExact(received, r.getAmountMinor());
            if (CASH.equals(r.getMethod())) {
                cash = Math.addExact(cash, r.getAmountMinor());
            }
            if (receivedAt == null || r.getReceivedAt().isAfter(receivedAt)) {
                receivedAt = r.getReceivedAt();
            }
        }
        if (received < due) {
            throw BizException.of(ErrorCode.POSTPAY_RECEIPT_UNDERPAID, money(due - received, c.currency()));
        }
        long change = received - due;
        if (change > cash) {
            throw BizException.of(ErrorCode.POSTPAY_RECEIPT_OVERPAID_NON_CASH);
        }

        // 5. 写实收，同一事务里把每张核心单推到已付；任何一张没推成就整笔回滚
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        var row = new OrdPostpayReceipt();
        row.setTenantNo("MAIN");
        row.setReceiptNo(BizKey.next(BizKey.POSTPAY_RECEIPT));
        row.setEntityNo(c.entityNo());
        row.setStoreNo(c.storeNo());
        row.setBizNo(c.bizNo());
        row.setCurrency(c.currency());
        row.setDueMinor(due);
        row.setReceivedMinor(received);
        row.setChangeMinor(change);
        row.setRequestKeyHash(sha256(c.requestKey()));
        row.setPayloadHash(payload);
        row.setActorNo(c.actorNo());
        row.setReceiptProvenance(OrdPostpayReceipt.PROVENANCE);
        row.setReceivedAt(receivedAt);
        row.setRecordedAt(now);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        try {
            receipts.insert(row);
        } catch (DuplicateKeyException sameKeyOtherSettlement) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        long paidAt = receivedAt.toInstant(ZoneOffset.UTC).toEpochMilli();
        long recordedAt = clock.instant().toEpochMilli();
        for (Root root : c.roots()) {
            OrdOrder order = byNo.get(root.orderNo());
            receipts.insertRoot(row.getReceiptNo(), root.orderNo(), root.dueMinor(), root.coreVersion());
            if (receipts.markPaid(root.orderNo(), order.getVersion(), paidAt, c.actorNo(), recordedAt) != 1) {
                throw BizException.of(ErrorCode.POSTPAY_RECEIPT_ORDER_CHANGED);
            }
            log(root.orderNo(), c.actorNo(), recordedAt);
        }
        for (OrdOfflineRecord r : records) {
            receipts.insertRecord(row.getReceiptNo(), r.getRecordNo(), r.getMethod(), r.getAmountMinor());
        }
        events.publish(new PostpayReceiptPort.Recorded(row.getReceiptNo(), c.entityNo(), c.storeNo(), c.bizNo(),
                c.roots().stream().map(Root::orderNo).toList()));
        return receipt(row, c.roots().stream().map(Root::orderNo).toList(), false);
    }

    private static void validate(Confirm c) {
        if (c == null || !"MAIN".equals(c.tenantNo()) || !token(c.entityNo()) || !token(c.storeNo())
                || !token(c.bizNo()) || !token(c.actorNo()) || c.requestKey() == null || c.requestKey().isBlank()
                || c.requestKey().length() > 128 || c.currency() == null || !c.currency().matches("[A-Z]{3}")
                || c.roots() == null || c.roots().isEmpty() || c.roots().size() > MAX_ROOTS
                || c.offlineRecordNos() == null || c.offlineRecordNos().isEmpty()
                || c.offlineRecordNos().size() > MAX_RECORDS) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        Set<String> roots = new HashSet<>();
        for (Root r : c.roots()) {
            if (r == null || !token(r.orderNo()) || r.dueMinor() < 0 || r.coreVersion() == null
                    || r.coreVersion().isBlank() || r.coreVersion().length() > 64 || !roots.add(r.orderNo())) {
                throw BizException.of(ErrorCode.BAD_REQUEST);
            }
        }
        if (new HashSet<>(c.offlineRecordNos()).size() != c.offlineRecordNos().size()
                || !c.offlineRecordNos().stream().allMatch(PostpayReceiptServiceImpl::token)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }

    private static boolean token(String s) {
        return s != null && TOKEN.matcher(s).matches();
    }

    /** 请求内容指纹：结算单号、币种、每张单的应收与版本、记账号集合（顺序无关）。 */
    static String payloadHash(Confirm c) {
        var roots = new ArrayList<>(c.roots());
        roots.sort(Comparator.comparing(Root::orderNo));
        StringBuilder s = new StringBuilder(c.storeNo()).append('|').append(c.bizNo()).append('|').append(c.currency());
        roots.forEach(r -> s.append('|').append(r.orderNo()).append(':').append(r.dueMinor()).append(':')
                .append(r.coreVersion()));
        c.offlineRecordNos().stream().sorted().forEach(r -> s.append("|R").append(r));
        return sha256(s.toString());
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** 差额文案用的金额：按币种的小数位，例如 12.50 CNY。 */
    static String money(long minor, String currency) {
        int digits;
        try {
            digits = Math.max(0, Currency.getInstance(currency).getDefaultFractionDigits());
        } catch (IllegalArgumentException unknown) {
            digits = 2;
        }
        return BigDecimal.valueOf(minor, digits).toPlainString() + " " + currency;
    }

    private void log(String orderNo, String actor, long at) {
        var log = new OrdStatusLog();
        log.setTenantNo("MAIN");
        log.setSubOrderNo(orderNo);
        log.setAggregateType("PAYMENT");
        log.setAggregateNo(orderNo);
        log.setEvent("POSTPAY_RECEIPT");
        log.setFromStatus("WAIT_PAY");
        log.setStatus("PAID");
        log.setLabel("后付人工实收");
        log.setOperatorType(OrdStatusLog.BY_MERCHANT);
        log.setOperatorNo(actor);
        log.setAt(at);
        log.setCreatedAt(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
        logs.insert(log);
    }

    private static Receipt receipt(OrdPostpayReceipt row, List<String> orders, boolean replayed) {
        return new Receipt(row.getReceiptNo(), row.getBizNo(), row.getCurrency(), row.getDueMinor(),
                row.getReceivedMinor(), row.getChangeMinor(), List.copyOf(orders), replayed);
    }

}
