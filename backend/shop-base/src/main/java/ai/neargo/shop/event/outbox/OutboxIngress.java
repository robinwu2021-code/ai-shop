package ai.neargo.shop.event.outbox;

/** Ingress commits in the target database before the source acknowledges delivery. */
public interface OutboxIngress {
    PublishReceipt accept(OutboxEnvelope sourceEvent);
}
