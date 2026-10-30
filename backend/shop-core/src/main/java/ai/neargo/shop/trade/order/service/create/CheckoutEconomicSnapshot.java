package ai.neargo.shop.trade.order.service.create;

import ai.neargo.shop.spi.marketing.CampaignPort;
import ai.neargo.shop.spi.marketing.CouponPort;
import ai.neargo.shop.spi.trade.CheckoutPort.QuoteLine;
import com.fasterxml.jackson.annotation.JsonInclude;

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
        CouponPort.Allocation coupon,
        @JsonInclude(JsonInclude.Include.NON_NULL) CampaignPort.CounterActivitySeal counterActivitySeal) {
    /** Preserve old serializer property order and omission for ordinary, M1 and frozen old counter economics. */
    public CheckoutEconomicSnapshot(
            String quoteHash, Evidence evidence, String buyerNo, String entityNo, String storeNo,
            String currency, String payMode, String fulfillment, String fulfillmentAuthority,
            List<QuoteLine> lines, long goodsMinor, long freightMinor, long discountMinor, long dueMinor,
            long platformMinor, long merchantMinor, CampaignPort.Discount activity, CouponPort.Allocation coupon) {
        this(quoteHash, evidence, buyerNo, entityNo, storeNo, currency, payMode, fulfillment, fulfillmentAuthority,
                lines, goodsMinor, freightMinor, discountMinor, dueMinor, platformMinor, merchantMinor, activity,
                coupon, null);
    }

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
