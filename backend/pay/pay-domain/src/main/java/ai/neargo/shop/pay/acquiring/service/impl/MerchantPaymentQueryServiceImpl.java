package ai.neargo.shop.pay.acquiring.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.access.PayAction;
import ai.neargo.shop.pay.access.PayCaller;
import ai.neargo.shop.pay.access.policy.CallerPolicy;
import ai.neargo.shop.pay.acquiring.entity.PayOrder;
import ai.neargo.shop.pay.acquiring.entity.PayRefund;
import ai.neargo.shop.pay.acquiring.service.MerchantPaymentQueryService;
import ai.neargo.shop.pay.checkout.entity.PayQrCode;
import ai.neargo.shop.pay.entity.StlPayment;
import ai.neargo.shop.pay.funds.BizDates;
import ai.neargo.shop.pay.ledger.entity.PayLedgerEntry;
import ai.neargo.shop.pay.ledger.entity.PayLedgerVoucher;
import ai.neargo.shop.pay.mapper.SettleMappers.PaymentMapper;
import ai.neargo.shop.pay.mapper.acquiring.AcquiringMappers.PayOrderMapper;
import ai.neargo.shop.pay.mapper.acquiring.AcquiringMappers.PayRefundMapper;
import ai.neargo.shop.pay.mapper.checkout.CheckoutMappers.PayQrCodeMapper;
import ai.neargo.shop.pay.mapper.ledger.LedgerMappers.LedgerEntryMapper;
import ai.neargo.shop.pay.mapper.ledger.LedgerMappers.LedgerVoucherMapper;
import ai.neargo.shop.pay.mapper.merchant.MerchantMappers.PayMerchantMapper;
import ai.neargo.shop.pay.mapper.terminal.TerminalMappers.PayTerminalMapper;
import ai.neargo.shop.pay.merchant.MerchantDefaults;
import ai.neargo.shop.pay.merchant.entity.PayMerchant;
import ai.neargo.shop.pay.merchant.service.PayMerchantBindingService;
import ai.neargo.shop.pay.terminal.entity.PayTerminal;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** {@link MerchantPaymentQueryService} 的实现。全是只读；每一个查询都带门店条件 */
@Service
public class MerchantPaymentQueryServiceImpl implements MerchantPaymentQueryService {

    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 50;
    /** 列表与汇总一次最多跨多少天（防一次扫全年） */
    private static final int MAX_DAYS = 93;
    private static final Pattern SAFE_Q = Pattern.compile("[A-Za-z0-9*\\-]{1,40}");
    private static final Pattern SAFE_SUFFIX = Pattern.compile("[A-Za-z0-9]{6,32}");

    private static final List<String> PENDING_RAW = List.of("CREATED", "AWAITING_PAYMENT", "AWAITING_CONFIRM", "AUTHORIZED");
    /** 这几种状态只有「至少开过一次渠道尝试」才算顾客真的付过 / 试过 */
    private static final List<String> NEEDS_ATTEMPT = List.of("CREATED", "AWAITING_PAYMENT", "CLOSED");
    private static final String HAS_ATTEMPT = "SELECT pay_order_id FROM stl_payment WHERE direction='PAY'"
            + " AND pay_order_id IS NOT NULL";

    private final PayOrderMapper orders;
    private final PayRefundMapper refunds;
    private final PaymentMapper attempts;
    private final PayQrCodeMapper qrCodes;
    private final PayTerminalMapper terminals;
    private final PayMerchantMapper merchants;
    private final LedgerEntryMapper entries;
    private final LedgerVoucherMapper vouchers;
    private final PayMerchantBindingService stores;
    private final CallerPolicy policy;
    private final long estimateRateBps;

    public MerchantPaymentQueryServiceImpl(PayOrderMapper orders, PayRefundMapper refunds, PaymentMapper attempts,
                                           PayQrCodeMapper qrCodes, PayTerminalMapper terminals,
                                           PayMerchantMapper merchants, LedgerEntryMapper entries,
                                           LedgerVoucherMapper vouchers, PayMerchantBindingService stores,
                                           CallerPolicy policy,
                                           @Value("${shop.pay.fee.estimate-rate-bps:0}") long estimateRateBps) {
        this.orders = orders;
        this.refunds = refunds;
        this.attempts = attempts;
        this.qrCodes = qrCodes;
        this.terminals = terminals;
        this.merchants = merchants;
        this.entries = entries;
        this.vouchers = vouchers;
        this.stores = stores;
        this.policy = policy;
        this.estimateRateBps = Math.max(0, estimateRateBps);
    }

    // ---------------------------------------------------------------- 列表

    @Override
    public PaymentPage list(PayCaller caller, Long storeId, PaymentFilter f) {
        policy.check(caller, PayAction.READ_LEDGER, storeId);
        String market = market(storeId);
        LocalDate from = f.from() == null ? today(market) : f.from();
        LocalDate to = f.to() == null ? from : f.to();
        checkRange(from, to);
        int size = f.size() == null ? DEFAULT_SIZE : Math.max(1, Math.min(f.size(), MAX_SIZE));

        LambdaQueryWrapper<PayOrder> w = visible(storeId)
                .ge(PayOrder::getCreatedAt, BizDates.startUtc(market, from))
                .lt(PayOrder::getCreatedAt, BizDates.startUtc(market, to.plusDays(1)));
        applySources(w, f.sources());
        applyStatuses(w, f.statuses());
        if (f.channels() != null && !f.channels().isEmpty()) {
            w.in(PayOrder::getPayChannel, f.channels());
        }
        if (f.amountMin() != null) {
            w.ge(PayOrder::getAmountMinor, f.amountMin());
        }
        if (f.amountMax() != null) {
            w.le(PayOrder::getAmountMinor, f.amountMax());
        }
        if (f.q() != null && !f.q().isBlank()) {
            String q = f.q().trim();
            if (!SAFE_Q.matcher(q).matches()) {
                throw BizException.of(ErrorCode.BAD_REQUEST);
            }
            w.and(x -> x.like(PayOrder::getPayOrderNo, q).or().like(PayOrder::getOutOrderNo, q)
                    .or().like(PayOrder::getPayerMasked, q)
                    .or().inSql(PayOrder::getId, HAS_ATTEMPT + " AND trade_no LIKE '%" + q + "%'"));
        }
        Cursor c = Cursor.parse(f.cursor());
        if (c != null) {
            w.and(x -> x.lt(PayOrder::getCreatedAt, c.at()).or(y -> y.eq(PayOrder::getCreatedAt, c.at())
                    .lt(PayOrder::getId, c.id())));
        }
        w.orderByDesc(PayOrder::getCreatedAt).orderByDesc(PayOrder::getId).last("LIMIT " + (size + 1));
        List<PayOrder> found = DataScopeContext.executeWithoutScope(() -> orders.selectList(w));
        boolean more = found.size() > size;
        List<PayOrder> page = more ? found.subList(0, size) : found;
        String next = more ? new Cursor(page.get(page.size() - 1).getCreatedAt(), page.get(page.size() - 1).getId()).encode()
                : null;
        return new PaymentPage(rows(page), next);
    }

    // ---------------------------------------------------------------- 汇总

    @Override
    public Summary summary(PayCaller caller, Long storeId, LocalDate from, LocalDate to) {
        policy.check(caller, PayAction.READ_LEDGER, storeId);
        String market = market(storeId);
        LocalDate f = from == null ? today(market) : from;
        LocalDate t = to == null ? f : to;
        checkRange(f, t);
        LocalDateTime start = BizDates.startUtc(market, f);
        LocalDateTime end = BizDates.startUtc(market, t.plusDays(1));
        List<PayOrder> paid = DataScopeContext.executeWithoutScope(() -> orders.selectList(notPartner(storeId)
                .isNotNull(PayOrder::getSucceededAt).ge(PayOrder::getSucceededAt, start).lt(PayOrder::getSucceededAt, end)));
        List<PayRefund> refunded = DataScopeContext.executeWithoutScope(() -> refunds.selectList(
                Wrappers.<PayRefund>lambdaQuery().eq(PayRefund::getStoreId, storeId).eq(PayRefund::getStatus, "SUCCEEDED")
                        .ge(PayRefund::getSucceededAt, start).lt(PayRefund::getSucceededAt, end)));

        Map<String, long[]> byChannel = new LinkedHashMap<>();
        paid.stream().sorted(Comparator.comparing(o -> String.valueOf(o.getPayChannel()))).forEach(o -> {
            long[] acc = byChannel.computeIfAbsent(String.valueOf(o.getPayChannel()), k -> new long[2]);
            acc[0]++;
            acc[1] += nz(o.getPaidMinor());
        });
        Map<Long, StlPayment> success = successAttempts(paid.stream().map(PayOrder::getId).toList());
        long fee = 0;
        boolean estimated = false;
        for (PayOrder o : paid) {
            StlPayment a = success.get(o.getId());
            if (a != null && a.getChannelFeeMinor() != null) {
                fee += a.getChannelFeeMinor();
            } else {
                fee += estimate(nz(o.getPaidMinor()));
                estimated = true;
            }
        }
        long collectedMinor = paid.stream().mapToLong(o -> nz(o.getPaidMinor())).sum();
        long refundedMinor = refunded.stream().mapToLong(r -> nz(r.getAmountMinor())).sum();
        long net = collectedMinor - refundedMinor;
        PayMerchant m = paid.isEmpty() ? null : merchant(paid.get(paid.size() - 1).getPayMerchantId());
        Settlement settlement = new Settlement("PENDING", net - fee, m == null ? null : expectedDate(t, m.getSettleCycle()),
                m == null ? null : m.getSettleAccountMasked());
        return new Summary(MerchantDefaults.currencyOf(market), f, t, new Count(paid.size(), collectedMinor),
                new Count(refunded.size(), refundedMinor), net,
                byChannel.entrySet().stream().map(e -> new ChannelCount(e.getKey(), (int) e.getValue()[0], e.getValue()[1]))
                        .toList(), fee, estimated, settlement);
    }

    // ---------------------------------------------------------------- 查找

    @Override
    public Lookup lookupByAmount(PayCaller caller, Long storeId, long amountMinor, String within) {
        policy.check(caller, PayAction.READ_LEDGER, storeId);
        if (amountMinor <= 0) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        String market = market(storeId);
        LocalDateTime since = switch (within == null ? "10m" : within) {
            case "10m" -> LocalDateTime.now(ZoneOffset.UTC).minusMinutes(10);
            case "1h" -> LocalDateTime.now(ZoneOffset.UTC).minusHours(1);
            case "today" -> BizDates.startUtc(market, today(market));
            default -> throw BizException.of(ErrorCode.BAD_REQUEST);
        };
        List<PayOrder> found = DataScopeContext.executeWithoutScope(() -> orders.selectList(visible(storeId)
                .eq(PayOrder::getAmountMinor, amountMinor).ge(PayOrder::getCreatedAt, since)
                .orderByDesc(PayOrder::getCreatedAt).orderByDesc(PayOrder::getId).last("LIMIT " + MAX_SIZE)));
        return split(found);
    }

    @Override
    public Lookup lookupByTradeNoSuffix(PayCaller caller, Long storeId, String suffix) {
        policy.check(caller, PayAction.READ_LEDGER, storeId);
        if (suffix == null || !SAFE_SUFFIX.matcher(suffix.trim()).matches()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        String s = suffix.trim();
        String market = market(storeId);
        LocalDateTime since = BizDates.startUtc(market, today(market).minusDays(1));
        List<PayOrder> found = DataScopeContext.executeWithoutScope(() -> orders.selectList(visible(storeId)
                .ge(PayOrder::getCreatedAt, since)
                .inSql(PayOrder::getId, HAS_ATTEMPT + " AND trade_no LIKE '%" + s + "'")
                .orderByDesc(PayOrder::getCreatedAt).orderByDesc(PayOrder::getId).last("LIMIT " + MAX_SIZE)));
        return split(found);
    }

    // ---------------------------------------------------------------- 详情

    @Override
    public Detail detail(PayCaller caller, Long storeId, String payOrderNo) {
        policy.check(caller, PayAction.READ_LEDGER, storeId);
        PayOrder o = payOrderNo == null ? null : DataScopeContext.executeWithoutScope(() -> orders.selectOne(
                Wrappers.<PayOrder>lambdaQuery().eq(PayOrder::getPayOrderNo, payOrderNo).eq(PayOrder::getStoreId, storeId)));
        if (o == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        List<StlPayment> tries = DataScopeContext.executeWithoutScope(() -> attempts.selectList(
                Wrappers.<StlPayment>lambdaQuery().eq(StlPayment::getPayOrderId, o.getId())
                        .eq(StlPayment::getDirection, StlPayment.PAY).orderByAsc(StlPayment::getId)));
        StlPayment ok = tries.stream().filter(a -> StlPayment.SUCCESS.equals(a.getStatus())).reduce((a, b) -> b).orElse(null);
        StlPayment last = tries.isEmpty() ? null : tries.get(tries.size() - 1);
        List<PayRefund> rs = DataScopeContext.executeWithoutScope(() -> refunds.selectList(
                Wrappers.<PayRefund>lambdaQuery().eq(PayRefund::getPayOrderId, o.getId()).orderByAsc(PayRefund::getId)));

        long paid = nz(o.getPaidMinor());
        long refunded = nz(o.getRefundedMinor());
        long refundable = Math.max(0, paid - refunded - nz(o.getReservedRefundMinor()));
        boolean feeKnown = ok != null && ok.getChannelFeeMinor() != null;
        long fee = paid == 0 ? 0 : feeKnown ? ok.getChannelFeeMinor() : estimate(paid);
        Amounts amounts = new Amounts(paid, refunded, refundable, fee, paid != 0 && !feeKnown, paid - refunded - fee);

        PayMerchant m = merchant(o.getPayMerchantId());
        String market = o.getMarket() == null ? market(storeId) : o.getMarket();
        Settlement settlement = o.getSucceededAt() == null ? null : new Settlement(settled(o.getId()) ? "SETTLED" : "PENDING",
                paid - refunded - fee,
                m == null ? null : expectedDate(BizDates.of(market, o.getSucceededAt().toInstant(ZoneOffset.UTC)),
                        m.getSettleCycle()),
                m == null ? null : m.getSettleAccountMasked());

        Payer payer = new Payer(o.getPayChannel(), o.getPayMethod(), o.getPayerMasked(), null, null, null, null);
        Refs refs = new Refs(o.getPayOrderNo(), ok == null ? (last == null ? null : last.getOutTradeNo()) : ok.getOutTradeNo(),
                ok == null ? null : ok.getTradeNo());
        Source source = source(o);
        Failure failure = ("FAILED".equals(o.getStatus()) || "CLOSED".equals(o.getStatus())) && last != null
                && (last.getErrCode() != null || last.getErrMsg() != null)
                ? new Failure(last.getErrCode(), last.getErrMsg()) : null;
        List<RefundItem> refundItems = rs.stream().map(r -> new RefundItem(r.getPayRefundNo(), nz(r.getAmountMinor()),
                r.getReason(), r.getRequestedBy(), r.getStatus(), epoch(r.getCreatedAt()), epoch(r.getSucceededAt()))).toList();

        List<Event> timeline = new ArrayList<>();
        timeline.add(new Event(epoch(o.getCreatedAt()), "CREATED", nz(o.getAmountMinor()), null));
        if (o.getSucceededAt() != null) {
            timeline.add(new Event(epoch(o.getSucceededAt()), "PAID", paid, ok == null ? null : ok.getTradeNo()));
        }
        if ("FAILED".equals(o.getStatus()) || "CLOSED".equals(o.getStatus())) {
            timeline.add(new Event(epoch(o.getClosedAt() == null ? o.getUpdatedAt() : o.getClosedAt()), o.getStatus(), null,
                    failure == null ? null : failure.code()));
        }
        for (PayRefund r : rs) {
            timeline.add(new Event(epoch(r.getCreatedAt()), "REFUND_REQUESTED", nz(r.getAmountMinor()), r.getPayRefundNo()));
            if (r.getSucceededAt() != null) {
                timeline.add(new Event(epoch(r.getSucceededAt()), "REFUND_SUCCEEDED", nz(r.getAmountMinor()),
                        r.getPayRefundNo()));
            }
        }
        timeline.sort(Comparator.comparing(Event::at, Comparator.nullsLast(Comparator.naturalOrder())));
        return new Detail(rows(List.of(o)).get(0), amounts, settlement, payer, refs, source, failure, refundItems, timeline);
    }

    // ---------------------------------------------------------------- 组装

    /** 本店、非第三方、不是「只开了单一次都没付」 */
    private LambdaQueryWrapper<PayOrder> visible(Long storeId) {
        return notPartner(storeId).and(x -> x.notIn(PayOrder::getStatus, NEEDS_ATTEMPT)
                .or().inSql(PayOrder::getId, HAS_ATTEMPT));
    }

    private static LambdaQueryWrapper<PayOrder> notPartner(Long storeId) {
        return Wrappers.<PayOrder>lambdaQuery().eq(PayOrder::getStoreId, storeId)
                .and(x -> x.isNull(PayOrder::getSourceType).or().ne(PayOrder::getSourceType, "PARTNER"));
    }

    private static void applySources(LambdaQueryWrapper<PayOrder> w, List<String> sources) {
        if (sources == null || sources.isEmpty()) {
            return;
        }
        w.and(x -> {
            boolean first = true;
            for (String s : sources) {
                if (!first) {
                    x.or();
                }
                first = false;
                switch (s) {
                    case "QR_CODE" -> x.eq(PayOrder::getBizType, "CHECKOUT_QR");
                    case "COLLECT" -> x.eq(PayOrder::getBizType, "BIZ_COLLECT");
                    case "POS" -> x.and(y -> y.eq(PayOrder::getSourceType, "TERMINAL").or().eq(PayOrder::getBizType, "IN_STORE"));
                    case "ORDER" -> x.and(y -> y.notIn(PayOrder::getBizType, "CHECKOUT_QR", "BIZ_COLLECT", "IN_STORE")
                            .and(z -> z.isNull(PayOrder::getSourceType).or().ne(PayOrder::getSourceType, "TERMINAL")));
                    default -> throw BizException.of(ErrorCode.BAD_REQUEST);
                }
            }
        });
    }

    private static void applyStatuses(LambdaQueryWrapper<PayOrder> w, List<String> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            return;
        }
        List<String> raw = new ArrayList<>();
        for (String s : statuses) {
            switch (s) {
                case "SUCCEEDED", "PARTIALLY_REFUNDED", "REFUNDED" -> raw.add(s);
                case "FAILED" -> raw.addAll(List.of("FAILED", "CLOSED"));
                case "PENDING" -> raw.addAll(PENDING_RAW);
                default -> throw BizException.of(ErrorCode.BAD_REQUEST);
            }
        }
        w.in(PayOrder::getStatus, raw);
    }

    private List<PaymentRow> rows(List<PayOrder> page) {
        if (page.isEmpty()) {
            return List.of();
        }
        Set<Long> terminalIds = page.stream().filter(o -> "TERMINAL".equals(o.getSourceType()) && o.getSourceId() != null)
                .map(PayOrder::getSourceId).collect(Collectors.toSet());
        Map<Long, String> sns = terminalIds.isEmpty() ? Map.of() : DataScopeContext.executeWithoutScope(
                () -> terminals.selectBatchIds(terminalIds)).stream()
                .collect(Collectors.toMap(PayTerminal::getId, PayTerminal::getTerminalSn, (a, b) -> a));
        return page.stream().map(o -> new PaymentRow(o.getPayOrderNo(), sourceKind(o), o.getPayChannel(), o.getPayMethod(),
                o.getCurrency(), nz(o.getAmountMinor()), nz(o.getPaidMinor()), nz(o.getRefundedMinor()), viewStatus(o.getStatus()),
                o.getPayerMasked(), null, sns.get(o.getSourceId()), bizRef(o), epoch(o.getSucceededAt()),
                epoch(o.getCreatedAt()))).toList();
    }

    private Lookup split(List<PayOrder> found) {
        List<PaymentRow> all = rows(found);
        return new Lookup(all.stream().filter(r -> !"PENDING".equals(r.status()) && !"FAILED".equals(r.status())).toList(),
                all.stream().filter(r -> "PENDING".equals(r.status())).toList());
    }

    private Source source(PayOrder o) {
        String kind = sourceKind(o);
        String qrKind = null;
        String tableRef = null;
        if ("QR_CODE".equals(kind) && o.getBizNo() != null) {
            PayQrCode qr = DataScopeContext.executeWithoutScope(() -> qrCodes.selectOne(
                    Wrappers.<PayQrCode>lambdaQuery().eq(PayQrCode::getQrCode, o.getBizNo())));
            if (qr != null) {
                qrKind = qr.getKind();
                tableRef = qr.getTableRef();
            }
        }
        String sn = null;
        if ("TERMINAL".equals(o.getSourceType()) && o.getSourceId() != null) {
            PayTerminal t = DataScopeContext.executeWithoutScope(() -> terminals.selectById(o.getSourceId()));
            sn = t == null ? null : t.getTerminalSn();
        }
        return new Source(kind, qrKind, tableRef, sn, o.getOperatorNo(), bizRef(o));
    }

    static String sourceKind(PayOrder o) {
        if ("CHECKOUT_QR".equals(o.getBizType())) {
            return "QR_CODE";
        }
        if ("BIZ_COLLECT".equals(o.getBizType())) {
            return "COLLECT";
        }
        if ("TERMINAL".equals(o.getSourceType()) || "IN_STORE".equals(o.getBizType())) {
            return "POS";
        }
        return "ORDER";
    }

    static String viewStatus(String raw) {
        if (raw == null) {
            return "PENDING";
        }
        return switch (raw) {
            case "SUCCEEDED", "PARTIALLY_REFUNDED", "REFUNDED" -> raw;
            case "FAILED", "CLOSED" -> "FAILED";
            default -> "PENDING";
        };
    }

    private static BizRef bizRef(PayOrder o) {
        return "ORDER".equals(sourceKind(o)) && o.getBizNo() != null ? new BizRef(o.getBizType(), o.getBizNo()) : null;
    }

    private Map<Long, StlPayment> successAttempts(Collection<Long> orderIds) {
        if (orderIds.isEmpty()) {
            return Map.of();
        }
        return DataScopeContext.executeWithoutScope(() -> attempts.selectList(Wrappers.<StlPayment>lambdaQuery()
                .in(StlPayment::getPayOrderId, orderIds).eq(StlPayment::getDirection, StlPayment.PAY)
                .eq(StlPayment::getStatus, StlPayment.SUCCESS))).stream()
                .collect(Collectors.toMap(StlPayment::getPayOrderId, Function.identity(), (a, b) -> b));
    }

    /** 这张单的资金是否已经进过结算凭证 */
    private boolean settled(Long payOrderId) {
        Set<Long> voucherIds = DataScopeContext.executeWithoutScope(() -> entries.selectList(
                Wrappers.<PayLedgerEntry>lambdaQuery().eq(PayLedgerEntry::getPayOrderId, payOrderId)))
                .stream().map(PayLedgerEntry::getPayLedgerVoucherId).collect(Collectors.toSet());
        return !voucherIds.isEmpty() && DataScopeContext.executeWithoutScope(() -> vouchers.selectCount(
                Wrappers.<PayLedgerVoucher>lambdaQuery().in(PayLedgerVoucher::getId, voucherIds)
                        .eq(PayLedgerVoucher::getVoucherType, "SETTLEMENT"))) > 0;
    }

    private PayMerchant merchant(Long id) {
        return id == null ? null : DataScopeContext.executeWithoutScope(() -> merchants.selectById(id));
    }

    /** T1 / T2 = 业务日后 1 / 2 天；WEEKLY = 下一个周一；没配按 T1 */
    static LocalDate expectedDate(LocalDate bizDate, String cycle) {
        if ("T2".equals(cycle)) {
            return bizDate.plusDays(2);
        }
        if ("WEEKLY".equals(cycle)) {
            return bizDate.with(TemporalAdjusters.next(DayOfWeek.MONDAY));
        }
        return bizDate.plusDays(1);
    }

    private long estimate(long paid) {
        return paid * estimateRateBps / 10_000;
    }

    private String market(Long storeId) {
        String m = stores.storeById(storeId).orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND)).market();
        return m == null ? "CN" : m;
    }

    private static LocalDate today(String market) {
        return BizDates.of(market, Instant.now());
    }

    private static void checkRange(LocalDate from, LocalDate to) {
        if (to.isBefore(from) || from.plusDays(MAX_DAYS).isBefore(to)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }

    private static Long epoch(LocalDateTime t) {
        return t == null ? null : t.toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    /** 游标：最后一行的创建时间（UTC 毫秒）+ id，形如 {@code 1793867171000_123} */
    record Cursor(LocalDateTime at, Long id) {

        String encode() {
            return at.toInstant(ZoneOffset.UTC).toEpochMilli() + "_" + id;
        }

        static Cursor parse(String s) {
            if (s == null || s.isBlank()) {
                return null;
            }
            String[] p = s.split("_");
            try {
                return new Cursor(LocalDateTime.ofInstant(Instant.ofEpochMilli(Long.parseLong(p[0])), ZoneOffset.UTC),
                        Long.parseLong(p[1]));
            } catch (RuntimeException e) {
                throw BizException.of(ErrorCode.BAD_REQUEST);
            }
        }
    }
}
