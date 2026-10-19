package ai.neargo.shop.event.outbox;

/** Repeated ingress returns the original local event identity. */
public record PublishReceipt(String eventNo, boolean created) { }
