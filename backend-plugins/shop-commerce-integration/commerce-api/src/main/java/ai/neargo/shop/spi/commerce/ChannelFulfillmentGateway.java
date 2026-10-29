package ai.neargo.shop.spi.commerce;

import java.time.Instant;
import java.util.List;

/**
 * Publishes committed original fulfillment facts; supplier acknowledgement never grants local
 * inventory authority.
 */
public interface ChannelFulfillmentGateway extends CommerceGateway {
    enum Stage {
        PREPARING,
        READY,
        HANDED_OVER,
        SHIPPED,
        DELIVERED,
        PICKED_UP
    }

    enum PublicationStatus {
        CONFIRMED,
        REJECTED,
        UNKNOWN
    }

    record FulfillmentLine(String externalLineRef, String quantity, String unit) {}

    record FulfillmentPublication(
            String operationNo,
            ChannelOrderGateway.Query scope,
            String originalOrderHash,
            String sourceFulfillmentRef,
            String sourceRevision,
            Stage stage,
            String packageRef,
            String carrierCode,
            String trackingNo,
            List<FulfillmentLine> lines,
            Instant occurredAt,
            String sourceReceiptRef) {
        public FulfillmentPublication {
            lines = List.copyOf(lines);
        }
    }

    record PublicationResult(
            String operationNo,
            String externalOrderRef,
            String publicationHash,
            PublicationStatus status,
            String originalReceiptRef) {}

    boolean supportsFulfillment(ChannelOrderGateway.Fulfillment fulfillment, Stage stage);

    PublicationResult publishFulfillment(FulfillmentPublication publication);

    /** Queries the exact frozen publication after uncertainty, without sending a second write. */
    PublicationResult queryFulfillment(FulfillmentPublication publication);
}
