package ai.neargo.shop.event.outbox;

import java.time.Instant;

/** Durable event identity is preserved across source-to-source forwarding. */
public record OutboxEnvelope(String eventNo, String sourceSystem, String sourceEventNo,
        String aggregateType, String aggregateId, String eventType, int schemaVersion,
        String streamKey, long streamSequence, String payload, Instant occurredAt) {
    public OutboxEnvelope {
        required(eventNo, 64); required(sourceSystem, 32); required(sourceEventNo, 64);
        required(aggregateType, 32); required(aggregateId, 64); required(eventType, 64);
        required(streamKey, 128);
        if (schemaVersion < 1 || streamSequence < 0 || payload == null || occurredAt == null) {
            throw new IllegalArgumentException("Invalid outbox envelope");
        }
    }
    private static void required(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new IllegalArgumentException("Invalid outbox business key (max " + max + ")");
        }
    }
}
