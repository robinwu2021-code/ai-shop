package ai.neargo.shop.event.outbox.runtime;

import ai.neargo.shop.event.outbox.OutboxEnvelope;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.databind.ObjectMapper;

/** Exact native store fingerprint shared with frozen historical-envelope verification. */
public final class OutboxFingerprint {
    private OutboxFingerprint() { }

    public static String fingerprint(ObjectMapper json, OutboxEnvelope e) {
        try {
            Object value = canonical(json.readValue(e.payload(), Object.class));
            String content = json.writeValueAsString(List.of(e.sourceSystem(), e.sourceEventNo(), e.eventType(),
                    e.schemaVersion(), e.aggregateType(), e.aggregateId(), e.streamKey(),
                            e.occurredAt().toString(), value));
            return sha256(content);
        } catch (Exception failure) {
            throw new IllegalArgumentException("Invalid outbox JSON", failure);
        }
    }

    public static Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, v) -> sorted.put(key.toString(), canonical(v)));
            return sorted;
        }
        if (value instanceof List<?> list) return list.stream().map(OutboxFingerprint::canonical).toList();
        if (value instanceof Number n) return new BigDecimal(n.toString()).stripTrailingZeros();
        return value;
    }

    public static String legacyChecksum(ObjectMapper json, String aggregateType, String aggregateId, String eventType,
            String payload, Instant createdAt) {
        return sha256(json.writeValueAsString(List.of(aggregateType, aggregateId, eventType,
                canonical(json.readValue(payload, Object.class)), createdAt.toString())));
    }

    public static String sha256(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
