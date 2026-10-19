package ai.neargo.shop.trade.order.refund.service;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.order.refund.model.RefundLine;
import ai.neargo.shop.trade.order.refund.model.RefundPlan;
import ai.neargo.shop.trade.order.refund.policy.LineShares;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

/** Phase one refunds the original child cash amount once, including its original freight and gift quantities. */
public final class BasicRefundPolicy {
    private BasicRefundPolicy() { }

    public static RefundPlan full(OrdSubOrder sub, List<OrdItem> items) {
        if (sub.getPayAmount() == null || sub.getPayAmount() < 0 || items.isEmpty()
                || nz(sub.getRefundedMinor()) != 0 || items.stream().anyMatch(i -> nz(i.getRefundedQty()) != 0
                        || nz(i.getRefundedMinor()) != 0)) {
            throw BizException.of(ErrorCode.AFTERSALE_AMOUNT_INVALID);
        }
        Map<Long, Long> shares = LineShares.of(sub, items);
        List<RefundLine> lines = items.stream().map(i -> new RefundLine(i.getId(), i.getQty(),
                shares.getOrDefault(i.getId(), 0L))).toList();
        long freight = sub.getPayAmount() - lines.stream().mapToLong(RefundLine::amountMinor).sum();
        if (freight < 0) {
            throw BizException.of(ErrorCode.AFTERSALE_AMOUNT_INVALID);
        }
        return new RefundPlan(sub.getSubOrderNo(), lines, freight);
    }

    public static void requireFull(RefundPlan full, RefundPlan requested) {
        if (full.totalMinor() != requested.totalMinor() || !full.lines().equals(requested.lines())) {
            throw BizException.of(ErrorCode.AFTERSALE_AMOUNT_INVALID);
        }
    }

    public static void validateRequest(RefundPlan full, List<RefundLine> requested, Long amount) {
        if (amount != null && (amount <= 0 || amount != full.totalMinor())) {
            throw BizException.of(ErrorCode.AFTERSALE_AMOUNT_INVALID);
        }
        if (requested == null || requested.isEmpty()) {
            return;
        }
        Map<Long, Integer> actual = new HashMap<>();
        for (RefundLine line : requested) {
            if (actual.putIfAbsent(line.itemId(), line.qty()) != null) {
                throw BizException.of(ErrorCode.AFTERSALE_AMOUNT_INVALID);
            }
        }
        if (actual.size() != full.lines().size() || full.lines().stream()
                .anyMatch(line -> !Integer.valueOf(line.qty()).equals(actual.get(line.itemId())))) {
            throw BizException.of(ErrorCode.AFTERSALE_AMOUNT_INVALID);
        }
    }

    /** A missing settlement bill is safe only when every frozen original monetary liability is known zero. */
    public static boolean zeroLiability(OrdSubOrder sub) {
        Number[] facts = {sub.getGoodsAmount(), sub.getFreightAmount(), sub.getPayAmount(),
                sub.getDiscountPlatform(), sub.getPointsDeductMinor(), sub.getPointsFeeMinor(),
                sub.getMerchantRecvMinor(), sub.getChannelFeeMinor(), sub.getCommissionMinor(), sub.getServiceFeeMinor()};
        for (Number fact : facts) {
            if (fact == null || fact.longValue() != 0) {
                return false;
            }
        }
        return true;
    }

    private static long nz(Number n) {
        return n == null ? 0 : n.longValue();
    }
}
