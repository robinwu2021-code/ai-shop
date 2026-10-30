package ai.neargo.shop.commerce.policy;

import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.spi.commerce.ChannelOrderGateway.Customer;
import ai.neargo.shop.spi.commerce.ChannelOrderGateway.Document;
import ai.neargo.shop.spi.commerce.ChannelOrderGateway.Fulfillment;
import ai.neargo.shop.spi.commerce.ChannelOrderGateway.Line;
import ai.neargo.shop.spi.commerce.ChannelOrderGateway.State;
import ai.neargo.shop.spi.commerce.ChannelOrderGateway.Totals;

import java.time.Instant;
import java.util.List;

/** Payment-state revisions cannot replace an invoice, recipient, schedule or physical selection. */
public final class ChannelOrderEconomicPolicy {
    private ChannelOrderEconomicPolicy() {}

    private record Business(
            int schemaVersion,
            String externalAccountRef,
            String externalStoreRef,
            String externalOrderRef,
            String catalogHash,
            String currency,
            Fulfillment fulfillment,
            Instant orderedAt,
            Instant requestedAt,
            Customer customer,
            List<Line> lines,
            Totals totals) {}

    /** Excludes only supplier revision/status/payment evidence; all business facts remain bound. */
    public static String fingerprint(Document document) {
        if (document == null) throw new IllegalArgumentException("Original order required");
        return PricingSnapshotHash.of(
                new Business(
                        document.schemaVersion(),
                        document.externalAccountRef(),
                        document.externalStoreRef(),
                        document.externalOrderRef(),
                        document.catalogHash(),
                        document.currency(),
                        document.fulfillment(),
                        document.orderedAt(),
                        document.requestedAt(),
                        document.customer(),
                        document.lines(),
                        document.totals()));
    }

    /**
     * Cancellation always needs the original withdrawal/after-sale owner, even when monetary totals
     * match.
     */
    public static boolean sameBusiness(Document original, Document current) {
        return original != null
                && current != null
                && original.state() != State.CANCELLED
                && current.state() != State.CANCELLED
                && fingerprint(original).equals(fingerprint(current));
    }
}
