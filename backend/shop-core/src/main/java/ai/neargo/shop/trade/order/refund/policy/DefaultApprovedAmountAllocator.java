package ai.neargo.shop.trade.order.refund.policy;

import ai.neargo.shop.trade.order.refund.model.RefundLine;
import ai.neargo.shop.trade.order.refund.model.RefundPlan;

import java.util.ArrayList;
import java.util.List;

/** 先抵货款（按各行申请额比例，最大余数法），抵完再抵运费；Σ 恰等于核定额。 */
public class DefaultApprovedAmountAllocator implements ApprovedAmountAllocator {

    @Override
    public RefundPlan allocate(RefundPlan requested, long approvedMinor) {
        if (approvedMinor >= requested.totalMinor()) {
            return requested;
        }
        long goods = Math.min(Math.max(0L, approvedMinor), requested.goodsMinor());
        long freight = Math.max(0L, approvedMinor - goods);
        long[] weights = requested.lines().stream().mapToLong(RefundLine::amountMinor).toArray();
        long[] shares = LineShares.allocate(goods, weights);
        List<RefundLine> lines = new ArrayList<>();
        for (int i = 0; i < weights.length; i++) {
            RefundLine r = requested.lines().get(i);
            lines.add(new RefundLine(r.itemId(), r.qty(), shares[i]));
        }
        return new RefundPlan(requested.subOrderNo(), lines, freight);
    }
}
