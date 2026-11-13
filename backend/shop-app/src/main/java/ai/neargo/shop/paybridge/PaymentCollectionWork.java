package ai.neargo.shop.paybridge;

import ai.neargo.shop.event.DomainEvent;

public record PaymentCollectionWork(String receiptNo) implements DomainEvent {
    public static final String TYPE = "MERCHANT_COLLECTION_RECEIVED";

    @Override
    public String aggregateType() {
        return "PAYMENT_COLLECTION";
    }

    @Override
    public String aggregateId() {
        return receiptNo;
    }

    @Override
    public String eventType() {
        return TYPE;
    }
}
