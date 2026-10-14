package ai.neargo.shop.trade.order.refund.model;

import java.util.List;

/** 一次退款的方案：退哪几行、各几件、货款多少、运费多少（值对象）。 */
public record RefundPlan(String subOrderNo, List<RefundLine> lines, long freightMinor) {

    public RefundPlan {
        lines = List.copyOf(lines);
    }

    public long goodsMinor() {
        return lines.stream().mapToLong(RefundLine::amountMinor).sum();
    }

    public long totalMinor() {
        return goodsMinor() + freightMinor;
    }

    /** 这次是否把所有行的剩余件数都退完 */
    public boolean coversAllRemaining(RefundableView view) {
        return view.lines().stream().filter(l -> l.remainingQty() > 0).allMatch(l -> lines.stream()
                .anyMatch(r -> r.itemId() == l.itemId() && r.qty() == l.remainingQty()));
    }
}
