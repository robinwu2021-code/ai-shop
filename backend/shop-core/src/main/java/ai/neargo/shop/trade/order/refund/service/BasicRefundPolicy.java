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
import java.util.ArrayList;
import java.util.Objects;
import java.util.HashSet;
import java.math.BigInteger;

/** Phase one refunds the original child cash amount once, including its original freight and gift quantities. */
public final class BasicRefundPolicy {
    private BasicRefundPolicy() { }

    public static RefundPlan full(OrdSubOrder sub, List<OrdItem> items) {
        if (nz(sub.getPointsDeduct()) < 0 || nz(sub.getPointsDeductMinor()) < 0 || nz(sub.getPointsFeeMinor()) < 0) {
            throw BizException.of(ErrorCode.ORDER_POINTS_MANUAL_REQUIRED);
        }
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

    /** Original first full path stays intact; partial/remainder requires complete immutable cash snapshots. */
    public static RefundPlan select(OrdSubOrder sub, List<OrdItem> items, List<RefundLine> requested, Long amount) {
        boolean untouched = nz(sub.getRefundedMinor()) == 0 && items.stream().allMatch(i ->
                nz(i.getRefundedQty()) == 0 && nz(i.getRefundedMinor()) == 0);
        if (untouched && (requested == null || requested.isEmpty()
                || exactWhole(items, requested))) {
            RefundPlan plan = full(sub, items);
            validateRequest(plan, requested, amount);
            return plan;
        }
        // Partial/remainder refunds only cover frozen, wholly unshipped original content.
        // Mixed fulfillment needs a separate quantity-to-issue proof before it can release holds safely.
        if (!OrdSubOrder.WAIT_FULFILL.equals(sub.getStatus())
                || items.stream().anyMatch(i -> !Integer.valueOf(0).equals(i.getShippedQty()))) {
            throw BizException.of(ErrorCode.ORDER_STATE_ILLEGAL);
        }
        if (items.isEmpty() || sub.getPayAmount() == null || sub.getPayAmount() < 0
                || nz(sub.getDiscountAmount()) != 0 || nz(sub.getDiscountPlatform()) != 0
                || nz(sub.getDiscountMerchant()) != 0 || nz(sub.getPointsDeduct()) != 0
                || nz(sub.getPointsDeductMinor()) != 0 || nz(sub.getPointsFeeMinor()) != 0)
            throw invalid();
        Map<Long, OrdItem> originals = new HashMap<>();
        long originalGoods = 0, refundedGoods = 0;
        for (OrdItem item : items) {
            if (item.getId() == null || item.getQty() == null || item.getQty() <= 0
                    || item.getPaidShareMinor() == null || item.getPaidShareMinor() < 0
                    || nz(item.getRefundedQty()) < 0 || nz(item.getRefundedQty()) > item.getQty()
                    || nz(item.getRefundedMinor()) != prefix(item.getPaidShareMinor(), nz(item.getRefundedQty()), item.getQty())
                    || originals.putIfAbsent(item.getId(), item) != null) throw invalid();
            originalGoods = Math.addExact(originalGoods, item.getPaidShareMinor());
            refundedGoods = Math.addExact(refundedGoods, nz(item.getRefundedMinor()));
        }
        long freight = nz(sub.getFreightAmount());
        long refundedFreight = nz(sub.getRefundedMinor()) - refundedGoods;
        if (freight < 0 || Math.addExact(originalGoods, freight) != sub.getPayAmount()
                || refundedFreight < 0 || refundedFreight > freight) throw invalid();
        List<RefundLine> wanted = requested == null || requested.isEmpty() ? items.stream()
                .filter(i -> nz(i.getRefundedQty()) < i.getQty())
                .map(i -> new RefundLine(i.getId(), i.getQty() - (int) nz(i.getRefundedQty()), 0)).toList() : requested;
        var seen = new HashSet<Long>();
        var lines = new ArrayList<RefundLine>();
        for (RefundLine request : wanted) {
            OrdItem original = originals.get(request.itemId());
            if (original == null || !seen.add(request.itemId()) || request.qty() <= 0
                    || request.qty() > original.getQty() - nz(original.getRefundedQty())) throw invalid();
            long prior = nz(original.getRefundedQty());
            long cash = prefix(original.getPaidShareMinor(), prior + request.qty(), original.getQty())
                    - prefix(original.getPaidShareMinor(), prior, original.getQty());
            lines.add(new RefundLine(request.itemId(), request.qty(), cash));
        }
        if (lines.isEmpty()) throw invalid();
        boolean all = items.stream().filter(i -> nz(i.getRefundedQty()) < i.getQty()).allMatch(i -> lines.stream()
                .anyMatch(l -> l.itemId() == i.getId() && l.qty() == i.getQty() - nz(i.getRefundedQty())));
        RefundPlan plan = new RefundPlan(sub.getSubOrderNo(), lines, all ? freight - refundedFreight : 0);
        if (amount != null && amount != plan.totalMinor()) throw invalid();
        return plan;
    }

    private static boolean exactWhole(List<OrdItem> items, List<RefundLine> requested) {
        Map<Long, Integer> quantities = new HashMap<>();
        for (RefundLine line : requested) if (quantities.putIfAbsent(line.itemId(), line.qty()) != null) return false;
        return quantities.size() == items.size() && items.stream().allMatch(i ->
                Objects.equals(quantities.get(i.getId()), i.getQty()));
    }

    public static void verify(OrdSubOrder sub, List<OrdItem> items, RefundPlan frozen) {
        RefundPlan expected = select(sub, items, frozen.lines(), null);
        if (expected.freightMinor() != frozen.freightMinor() || !expected.lines().equals(frozen.lines())) throw invalid();
    }

    public static long prefix(long total, long qty, long originalQty) {
        if (total < 0 || qty < 0 || originalQty <= 0 || qty > originalQty) throw invalid();
        return BigInteger.valueOf(total).multiply(BigInteger.valueOf(qty))
                .divide(BigInteger.valueOf(originalQty)).longValueExact();
    }

    private static BizException invalid() { return BizException.of(ErrorCode.AFTERSALE_AMOUNT_INVALID); }

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
