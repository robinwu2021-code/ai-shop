package ai.neargo.shop.commerce.policy;

import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.spi.commerce.ChannelFulfillmentGateway.FulfillmentPublication;
import ai.neargo.shop.spi.commerce.ChannelFulfillmentGateway.PublicationResult;
import ai.neargo.shop.spi.commerce.ChannelFulfillmentGateway.PublicationStatus;
import ai.neargo.shop.spi.commerce.ChannelFulfillmentGateway.Stage;
import ai.neargo.shop.spi.commerce.ChannelOrderGateway.Document;
import ai.neargo.shop.spi.commerce.ChannelOrderGateway.Fulfillment;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.HashSet;
import java.util.Set;

/**
 * Exact physical quantities and source evidence stay distinct from preparation, shipping and
 * delivery acknowledgements.
 */
public final class ChannelFulfillmentPolicy {
    private ChannelFulfillmentPolicy() {}

    /** Revalidates the publication against its original platform revision, not today's catalog. */
    public static void validate(FulfillmentPublication p, Document original, Clock clock) {
        require(p != null && p.scope() != null && original != null);
        CommerceValidation.text(p.operationNo(), 32);
        CommerceValidation.text(p.sourceFulfillmentRef(), 128);
        CommerceValidation.text(p.sourceRevision(), 128);
        CommerceValidation.text(p.sourceReceiptRef(), 256);
        var q = p.scope();
        CommerceValidation.text(q.entityNo(), 32);
        CommerceValidation.text(q.storeNo(), 32);
        CommerceValidation.text(q.connectionNo(), 32);
        CommerceValidation.text(q.profileNo(), 32);
        CommerceValidation.text(q.externalAccountRef(), 128);
        CommerceValidation.text(q.externalStoreRef(), 128);
        CommerceValidation.text(q.externalOrderRef(), 128);
        CommerceValidation.text(q.externalRevision(), 128);
        require(
                q.externalAccountRef().equals(original.externalAccountRef())
                        && q.externalStoreRef().equals(original.externalStoreRef())
                        && q.externalOrderRef().equals(original.externalOrderRef())
                        && q.externalRevision().equals(original.revisionRef())
                        && PricingSnapshotHash.of(original).equals(p.originalOrderHash())
                        && supports(original.fulfillment(), p.stage())
                        && p.occurredAt() != null
                        && !p.occurredAt().isAfter(clock.instant())
                        && p.lines() != null
                        && !p.lines().isEmpty()
                        && p.lines().size() <= 1000);
        if (original.fulfillment() == Fulfillment.SHIPMENT) {
            CommerceValidation.text(p.packageRef(), 128);
            CommerceValidation.text(p.carrierCode(), 64);
            CommerceValidation.text(p.trackingNo(), 128);
        } else require(p.packageRef() == null && p.carrierCode() == null && p.trackingNo() == null);
        var seen = new HashSet<String>();
        for (var line : p.lines()) {
            require(line != null);
            CommerceValidation.text(line.externalLineRef(), 128);
            CommerceValidation.text(line.unit(), 16);
            ChannelOrderPolicy.quantity(line.quantity());
            require(seen.add(line.externalLineRef()));
            var ordered =
                    original.lines().stream()
                            .filter(l -> l.lineRef().equals(line.externalLineRef()))
                            .findFirst()
                            .orElse(null);
            require(
                    ordered != null
                            && ordered.unit().equals(line.unit())
                            && new BigDecimal(line.quantity())
                                            .compareTo(new BigDecimal(ordered.quantity()))
                                    <= 0);
        }
    }

    /**
     * Capabilities distinguish delivery from handover and never flatten shipment into a restaurant
     * status.
     */
    public static boolean supports(Fulfillment fulfillment, Stage stage) {
        if (fulfillment == null || stage == null) return false;
        return switch (fulfillment) {
            case INSTANT_DELIVERY ->
                    Set.of(Stage.PREPARING, Stage.READY, Stage.HANDED_OVER, Stage.DELIVERED)
                            .contains(stage);
            case PICKUP -> Set.of(Stage.PREPARING, Stage.READY, Stage.PICKED_UP).contains(stage);
            case SHIPMENT -> stage == Stage.SHIPPED || stage == Stage.DELIVERED;
            case VOUCHER -> false;
        };
    }

    /**
     * A receipt is accepted only for the exact original operation and complete immutable payload.
     */
    public static boolean confirms(FulfillmentPublication p, PublicationResult result) {
        if (result == null
                || result.status() != PublicationStatus.CONFIRMED
                || !p.operationNo().equals(result.operationNo())
                || !p.scope().externalOrderRef().equals(result.externalOrderRef())
                || !PricingSnapshotHash.of(p).equals(result.publicationHash())) return false;
        CommerceValidation.text(result.originalReceiptRef(), 256);
        return true;
    }

    private static void require(boolean valid) {
        if (!valid) throw new IllegalStateException("Invalid original fulfillment evidence");
    }
}
