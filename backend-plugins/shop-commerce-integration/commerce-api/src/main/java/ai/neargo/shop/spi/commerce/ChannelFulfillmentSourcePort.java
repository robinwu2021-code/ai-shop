package ai.neargo.shop.spi.commerce;

import ai.neargo.shop.spi.commerce.ChannelFulfillmentGateway.Stage;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;

import java.time.Instant;
import java.util.List;

/**
 * Only the original business owner can attest committed physical fulfillment, including its
 * immutable line allocations.
 */
public interface ChannelFulfillmentSourcePort {
    Owner owner();

    Facts capture(Query query);

    record Query(
            String entityNo,
            String storeNo,
            String sourceOrderRef,
            String coreOrderRef,
            String sourceFulfillmentRef,
            String sourceRevision) {}

    record Line(
            String sourceLineRef,
            String componentRef,
            String coreItemRef,
            String quantity,
            String unit) {}

    record Data(
            Query query,
            Stage stage,
            String packageRef,
            String carrierCode,
            String trackingNo,
            List<Line> lines,
            Instant occurredAt,
            String sourceReceiptRef) {
        public Data {
            lines = List.copyOf(lines);
        }
    }

    /**
     * sourceReceiptRef is the stable committed fact; the short validation receipt never changes
     * that fact's contents.
     */
    record Facts(
            Data data, Instant observedAt, Instant validUntil, String verificationReceiptRef) {}
}
