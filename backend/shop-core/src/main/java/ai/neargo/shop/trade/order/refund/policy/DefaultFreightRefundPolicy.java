package ai.neargo.shop.trade.order.refund.policy;

import java.util.Set;

/**
 * 未发货且退完全部剩余 → 退运费；已发货 → 只有商家责任原因（损坏 / 质量 / 错发 / 少发）才退；只退一次。
 */
public class DefaultFreightRefundPolicy implements FreightRefundPolicy {

    static final Set<String> MERCHANT_FAULT = Set.of("DAMAGED", "QUALITY", "WRONG_ITEM", "MISSING");

    @Override
    public long freightFor(long freightRemainingMinor, boolean shipped, boolean coversAllRemaining, String reasonCode) {
        if (freightRemainingMinor <= 0) {
            return 0L;
        }
        if (!shipped) {
            return coversAllRemaining ? freightRemainingMinor : 0L;
        }
        return MERCHANT_FAULT.contains(reasonCode) ? freightRemainingMinor : 0L;
    }
}
