package ai.neargo.shop.food.dto;

import ai.neargo.shop.common.pricing.PricingSnapshotHash;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Genuine original custody evidence. Public opaque core item references come from the original
 * acceptance allocation.
 */
public final class DiningChannelFulfillmentFacts {
    private DiningChannelFulfillmentFacts() {}

    public record Query(
            String entityNo,
            String storeNo,
            String batchNo,
            String coreOrderNo,
            String custodyOperationNo,
            String sourceRevision) {}

    public record Line(
            String sourceLineRef,
            String componentRef,
            String coreItemRef,
            BigDecimal quantity,
            String unit) {}

    public record Facts(
            Query query,
            List<Line> lines,
            Instant occurredAt,
            String sourceReceiptRef,
            Instant observedAt,
            Instant validUntil) {
        public Facts {
            lines = List.copyOf(lines);
        }
    }

    /**
     * Revision binds every field of the original immutable committed custody receipt, including all
     * physical components.
     */
    public static String revision(KitchenContracts.CustodyReceiptVO receipt) {
        return "CUSTODY_V1:" + PricingSnapshotHash.of(receipt);
    }
}
