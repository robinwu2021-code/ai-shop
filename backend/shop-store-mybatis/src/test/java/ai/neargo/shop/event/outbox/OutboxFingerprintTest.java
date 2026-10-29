package ai.neargo.shop.event.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import ai.neargo.shop.event.outbox.runtime.OutboxFingerprint;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class OutboxFingerprintTest {
    private final ObjectMapper json = new ObjectMapper();

    private OutboxEnvelope envelope(String payload, long sequence, String sourceEventNo) {
        return new OutboxEnvelope("EVENT", "PLATFORM", sourceEventNo, "INVENTORY", "ORDER", "RESERVE", 1,
                "ORDER:ORDER", sequence, payload, Instant.parse("2026-10-29T00:00:00Z"));
    }

    @Test
    void sharedFingerprintNormalizesMapsAndNumbersButPreservesArraysAndSourceMetadata() {
        var first = OutboxFingerprint.fingerprint(json, envelope("{\"b\":2.0,\"a\":[1,2]}", 1, "SOURCE"));
        assertThat(first).isEqualTo(OutboxFingerprint.fingerprint(json,
                envelope("{\"a\":[1,2],\"b\":2}", 99, "SOURCE")));
        assertThat(first).isNotEqualTo(OutboxFingerprint.fingerprint(json,
                envelope("{\"a\":[2,1],\"b\":2}", 1, "SOURCE")));
        assertThat(first).isNotEqualTo(OutboxFingerprint.fingerprint(json,
                envelope("{\"a\":[1,2],\"b\":2}", 1, "OTHER")));
    }
    @Test
    void everyNativeFingerprintMetadataFieldIsBoundWhileAdoptionChecksumAndRawBytesStaySeparate() {
        var original = envelope("{\"n\":1}", 1, "SOURCE");
        var time = original.occurredAt();
        var mutations = java.util.List.of(
                new OutboxEnvelope("EVENT", "OTHER", "SOURCE", "INVENTORY", "ORDER", "RESERVE", 1,
                        "ORDER:ORDER", 1, original.payload(), time),
                new OutboxEnvelope("EVENT", "PLATFORM", "SOURCE", "OTHER", "ORDER", "RESERVE", 1,
                        "ORDER:ORDER", 1, original.payload(), time),
                new OutboxEnvelope("EVENT", "PLATFORM", "SOURCE", "INVENTORY", "OTHER", "RESERVE", 1,
                        "ORDER:ORDER", 1, original.payload(), time),
                new OutboxEnvelope("EVENT", "PLATFORM", "SOURCE", "INVENTORY", "ORDER", "ALLOCATE", 1,
                        "ORDER:ORDER", 1, original.payload(), time),
                new OutboxEnvelope("EVENT", "PLATFORM", "SOURCE", "INVENTORY", "ORDER", "RESERVE", 2,
                        "ORDER:ORDER", 1, original.payload(), time),
                new OutboxEnvelope("EVENT", "PLATFORM", "SOURCE", "INVENTORY", "ORDER", "RESERVE", 1,
                        "INVENTORY:ORDER", 1, original.payload(), time),
                new OutboxEnvelope("EVENT", "PLATFORM", "SOURCE", "INVENTORY", "ORDER", "RESERVE", 1,
                        "ORDER:ORDER", 1, original.payload(), time.plusMillis(1)));
        String hash = OutboxFingerprint.fingerprint(json, original);
        mutations.forEach(mutation -> assertThat(OutboxFingerprint.fingerprint(json, mutation)).isNotEqualTo(hash));
        String legacy = OutboxFingerprint.legacyChecksum(json, "INVENTORY", "ORDER", "RESERVE", original.payload(),
                java.sql.Timestamp.from(time).toInstant());
        assertThat(legacy).isNotEqualTo(hash).isNotEqualTo(OutboxFingerprint.sha256(original.payload()));
    }

    @Test
    void exactNativeFingerprintHasAnIndependentKnownConstant() {
        assertThat(OutboxFingerprint.fingerprint(json, envelope("{\"b\":2.0,\"a\":[1,2]}", 1, "SOURCE")))
                .isEqualTo("b01001782d11ecec6b9dc49207cb1889ce523fa6d35de88590fbf6d0ff4e3306");
    }

}
