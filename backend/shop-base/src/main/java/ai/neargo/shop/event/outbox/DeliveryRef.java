package ai.neargo.shop.event.outbox;

/** Source is part of identity: numeric delivery IDs overlap between databases. */
public record DeliveryRef(String source, long id) { }
