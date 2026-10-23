package ai.neargo.shop.trade.order.service.create;

import ai.neargo.shop.spi.marketing.CampaignPort;
import ai.neargo.shop.spi.marketing.CouponPort;
import ai.neargo.shop.spi.trade.CheckoutPort.QuoteLine;

import java.util.List;

/** Same calculation consumed by the actual ordinary order writer. No independent pricing rules. */
public record CheckoutEconomicSnapshot(
        String quoteHash,
        Evidence evidence,
        String buyerNo,
        String entityNo,
        String storeNo,
        String currency,
        String payMode,
        String fulfillment,
        String fulfillmentAuthority,
        List<QuoteLine> lines,
        long goodsMinor,
        long freightMinor,
        long discountMinor,
        long dueMinor,
        long platformMinor,
        long merchantMinor,
        CampaignPort.Discount activity,
        CouponPort.Allocation coupon) {
    /**
     * Preserve command, scope and presentation-version evidence alongside the actual writer money.
     */
    public record Evidence(
            String tenantNo,
            long scopeRevision,
            String scopeHash,
            String commandHash,
            String catalogVersionNo,
            long catalogRevision,
            String catalogHash) {}

    /** Freeze price lines before the same shares are used by the order item writer. */
    public CheckoutEconomicSnapshot {
        lines = List.copyOf(lines);
    }

    /**
     * Return the exact validated shares in original line order without running allocation again.
     */
    public long[] paidShares() {
        return lines.stream().mapToLong(QuoteLine::dueMinor).toArray();
    }
}
