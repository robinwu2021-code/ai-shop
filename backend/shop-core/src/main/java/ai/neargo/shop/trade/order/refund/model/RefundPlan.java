package ai.neargo.shop.trade.order.refund.model;

import java.util.List;
import ai.neargo.shop.spi.product.StockPort;

/** 一次退款的方案：退哪几行、各几件、货款多少、运费多少（值对象）。 */
public record RefundPlan(String subOrderNo, List<RefundLine> lines, long freightMinor,
                         List<OriginalPortion> originalPortions, List<StockPort.SkuQty> allocationReleaseTargets) {
    public RefundPlan(String subOrderNo, List<RefundLine> lines, long freightMinor) {
        this(subOrderNo, lines, freightMinor, List.of(), List.of());
    }
    public record OriginalPortion(long itemId, String sourceLineNo, String componentRef, int originalQty,
                                  int fromQty, int throughQty, long netMinor, long taxMinor, long cashMinor,
                                  String evidenceHash) { }

    public RefundPlan {
        lines = List.copyOf(lines);
        originalPortions = originalPortions == null ? List.of() : List.copyOf(originalPortions);
        allocationReleaseTargets = allocationReleaseTargets == null ? List.of() : List.copyOf(allocationReleaseTargets);
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

    /** 部分退款：没退完所有行的剩余件数，或货款被核定 / 协商压低（渠道不支持部分退时要拦） */
    public boolean isPartial(RefundableView view) {
        return !coversAllRemaining(view) || goodsMinor() < view.remainingGoodsMinor();
    }
}
