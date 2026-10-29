package ai.neargo.shop.spi.trade;

import ai.neargo.shop.event.DomainEvent;

import java.time.Instant;

/**
 * References only a newly committed original pickup/handover proof, never a supplier
 * acknowledgement.
 */
public record RetailChannelFulfillmentSealed(
        String tenantNo,
        String entityNo,
        String storeNo,
        String importRef,
        String sourceOrderRef,
        String orderNo,
        String fulfillmentNo,
        String revision,
        String stage,
        String receiptNo,
        String contentHash,
        Instant occurredAt)
        implements DomainEvent {
    public static final String TYPE = "RETAIL_CHANNEL_FULFILLMENT_SEALED";

    @Override
    public String aggregateType() {
        return "RETAIL_CHANNEL_FULFILLMENT";
    }

    @Override
    public String aggregateId() {
        return fulfillmentNo;
    }

    @Override
    public String eventType() {
        return TYPE;
    }
}
