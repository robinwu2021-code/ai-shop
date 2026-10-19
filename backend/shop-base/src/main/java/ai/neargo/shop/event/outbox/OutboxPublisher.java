package ai.neargo.shop.event.outbox;

import ai.neargo.shop.event.DomainEvent;

/** Publication joins the caller's source transaction, never an unrelated transaction. */
public interface OutboxPublisher {
    PublishReceipt publish(DomainEvent event, PublishOptions options);
}
