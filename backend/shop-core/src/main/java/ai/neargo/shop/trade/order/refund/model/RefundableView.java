package ai.neargo.shop.trade.order.refund.model;

import java.util.List;

/**
 * 一张订单此刻能退什么（值对象）：申请页实时算额与申请校验共用这一份。
 *
 * @param freightRemainingMinor 还没退过的运费（能不能退由 FreightRefundPolicy 按场景判）
 * @param deadlineAt            售后期截止时刻；null = 还没完成、不限
 * @param partialSupported      本单支付渠道能否原路部分退
 */
public record RefundableView(String subOrderNo, List<RefundableLine> lines, long freightRemainingMinor,
                             Long deadlineAt, boolean partialSupported) {

    public long remainingGoodsMinor() {
        return lines.stream().mapToLong(RefundableLine::remainingMinor).sum();
    }

    public RefundableLine line(long itemId) {
        return lines.stream().filter(l -> l.itemId() == itemId).findFirst().orElse(null);
    }
}
