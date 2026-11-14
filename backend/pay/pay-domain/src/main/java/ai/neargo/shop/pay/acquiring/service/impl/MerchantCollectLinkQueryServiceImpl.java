package ai.neargo.shop.pay.acquiring.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.access.PayAction;
import ai.neargo.shop.pay.access.PayCaller;
import ai.neargo.shop.pay.access.policy.CallerPolicy;
import ai.neargo.shop.pay.acquiring.entity.PayOrder;
import ai.neargo.shop.pay.acquiring.service.MerchantCollectLinkQueryService;
import ai.neargo.shop.pay.checkout.entity.PayCheckoutSession;
import ai.neargo.shop.pay.mapper.acquiring.AcquiringMappers.PayOrderMapper;
import ai.neargo.shop.pay.mapper.checkout.CheckoutMappers.PayCheckoutSessionMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** {@link MerchantCollectLinkQueryService} 的实现。只读；每个查询都带门店与 BIZ_COLLECT 两个条件 */
@Service
public class MerchantCollectLinkQueryServiceImpl implements MerchantCollectLinkQueryService {

    static final String COLLECT_BIZ_TYPE = "BIZ_COLLECT";
    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 50;
    private static final List<String> PAID = List.of("SUCCEEDED", "PARTIALLY_REFUNDED", "REFUNDED");
    private static final List<String> OPEN = List.of("CREATED", "AWAITING_PAYMENT", "AWAITING_CONFIRM", "AUTHORIZED");
    private static final String VOIDED_SQL = "SELECT pay_order_id FROM pay_checkout_session WHERE status = 'VOIDED'"
            + " AND deleted = 0";

    private final PayOrderMapper orders;
    private final PayCheckoutSessionMapper sessions;
    private final CallerPolicy policy;

    public MerchantCollectLinkQueryServiceImpl(PayOrderMapper orders, PayCheckoutSessionMapper sessions,
                                               CallerPolicy policy) {
        this.orders = orders;
        this.sessions = sessions;
        this.policy = policy;
    }

    @Override
    public LinkPage list(PayCaller caller, Long storeId, String status, String cursor, Integer size) {
        policy.check(caller, PayAction.READ_LEDGER, storeId);
        int n = size == null ? DEFAULT_SIZE : Math.max(1, Math.min(size, MAX_SIZE));
        LambdaQueryWrapper<PayOrder> w = links(storeId);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (status != null && !status.isBlank()) {
            switch (status) {
                case "PAID" -> w.in(PayOrder::getStatus, PAID);
                case "VOIDED" -> w.eq(PayOrder::getStatus, "CLOSED").inSql(PayOrder::getId, VOIDED_SQL);
                case "PENDING" -> w.in(PayOrder::getStatus, OPEN).gt(PayOrder::getExpireAt, now);
                case "EXPIRED" -> w.notIn(PayOrder::getStatus, PAID).notInSql(PayOrder::getId, VOIDED_SQL)
                        .and(x -> x.notIn(PayOrder::getStatus, OPEN).or().le(PayOrder::getExpireAt, now));
                default -> throw BizException.of(ErrorCode.BAD_REQUEST);
            }
        }
        MerchantPaymentQueryServiceImpl.Cursor c = MerchantPaymentQueryServiceImpl.Cursor.parse(cursor);
        if (c != null) {
            w.and(x -> x.lt(PayOrder::getCreatedAt, c.at()).or(y -> y.eq(PayOrder::getCreatedAt, c.at())
                    .lt(PayOrder::getId, c.id())));
        }
        w.orderByDesc(PayOrder::getCreatedAt).orderByDesc(PayOrder::getId).last("LIMIT " + (n + 1));
        List<PayOrder> found = DataScopeContext.executeWithoutScope(() -> orders.selectList(w));
        boolean more = found.size() > n;
        List<PayOrder> page = more ? found.subList(0, n) : found;
        String next = more ? new MerchantPaymentQueryServiceImpl.Cursor(page.get(n - 1).getCreatedAt(),
                page.get(n - 1).getId()).encode() : null;
        return new LinkPage(rows(page, now), next);
    }

    @Override
    public LinkRow row(PayCaller caller, Long storeId, String payOrderNo) {
        policy.check(caller, PayAction.READ_LEDGER, storeId);
        PayOrder o = payOrderNo == null ? null : DataScopeContext.executeWithoutScope(() -> orders.selectOne(
                links(storeId).eq(PayOrder::getPayOrderNo, payOrderNo)));
        if (o == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return rows(List.of(o), LocalDateTime.now(ZoneOffset.UTC)).get(0);
    }

    @Override
    public List<BizLink> ofBizNo(PayCaller caller, Long storeId, String bizNo) {
        policy.check(caller, PayAction.READ_LEDGER, storeId);
        if (bizNo == null || !bizNo.matches("[A-Za-z0-9_.:-]{1,64}")) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        List<PayOrder> found = DataScopeContext.executeWithoutScope(() -> orders.selectList(links(storeId)
                .eq(PayOrder::getBizNo, bizNo).orderByAsc(PayOrder::getCreatedAt).orderByAsc(PayOrder::getId)
                .last("LIMIT 50")));
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<LinkRow> rows = rows(found, now);
        java.util.ArrayList<BizLink> out = new java.util.ArrayList<>(found.size());
        for (int i = 0; i < found.size(); i++) {
            PayOrder o = found.get(i);
            LinkRow r = rows.get(i);
            out.add(new BizLink(o.getPayOrderNo(), r.status(), o.getCurrency(), r.amountMinor(),
                    o.getPaidMinor() == null ? 0 : o.getPaidMinor(),
                    o.getRefundedMinor() == null ? 0 : o.getRefundedMinor(), r.createdAt(), r.paidAt()));
        }
        return out;
    }

    /** 本店的付款链接 —— 消融点：去掉 biz_type 条件，静态码等别的来源就混进来 */
    private static LambdaQueryWrapper<PayOrder> links(Long storeId) {
        return Wrappers.<PayOrder>lambdaQuery().eq(PayOrder::getStoreId, storeId).eq(PayOrder::getBizType, COLLECT_BIZ_TYPE);
    }

    private List<LinkRow> rows(List<PayOrder> page, LocalDateTime now) {
        if (page.isEmpty()) {
            return List.of();
        }
        Set<Long> ids = page.stream().map(PayOrder::getId).collect(Collectors.toSet());
        Map<Long, String> voidedBy = DataScopeContext.executeWithoutScope(() -> sessions.selectList(
                Wrappers.<PayCheckoutSession>lambdaQuery().in(PayCheckoutSession::getPayOrderId, ids)
                        .eq(PayCheckoutSession::getStatus, "VOIDED"))).stream()
                .collect(Collectors.toMap(PayCheckoutSession::getPayOrderId,
                        x -> x.getUpdatedBy() == null ? "" : x.getUpdatedBy(), (a, b) -> a));
        return page.stream().map(o -> {
            String st = status(o, voidedBy.containsKey(o.getId()), now);
            return new LinkRow(o.getPayOrderNo(), o.getAmountMinor() == null ? 0 : o.getAmountMinor(), o.getCurrency(),
                    o.getSubject(), st, MerchantPaymentQueryServiceImpl.auditEpoch(o.getCreatedAt()), epoch(o.getExpireAt()), epoch(o.getSucceededAt()),
                    o.getPayChannel(), o.getOperatorNo(), "VOIDED".equals(st) ? voidedBy.get(o.getId()) : null);
        }).toList();
    }

    static String status(PayOrder o, boolean voided, LocalDateTime now) {
        if (PAID.contains(o.getStatus())) {
            return "PAID";
        }
        if ("CLOSED".equals(o.getStatus()) && voided) {
            return "VOIDED";
        }
        if (OPEN.contains(o.getStatus()) && o.getExpireAt() != null && o.getExpireAt().isAfter(now)) {
            return "PENDING";
        }
        return "EXPIRED";
    }

    private static Long epoch(LocalDateTime t) {
        return t == null ? null : t.toInstant(ZoneOffset.UTC).toEpochMilli();
    }
}
