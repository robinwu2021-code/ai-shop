package ai.neargo.shop.event.outbox;

/** Stable IDs survive bean proxies and code refactoring; business effects remain idempotent. */
public interface OutboxHandler {
    String handlerId();
    boolean supports(String eventType);
    default boolean externalWorkflow() { return false; }
    DeliveryResult handle(OutboxEnvelope event);
}
