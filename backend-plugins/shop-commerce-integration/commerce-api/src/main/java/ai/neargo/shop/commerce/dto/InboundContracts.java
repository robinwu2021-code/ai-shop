package ai.neargo.shop.commerce.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import tools.jackson.databind.annotation.JsonDeserialize;

import java.time.Instant;

/**
 * Receipt metadata is public to the scoped operator; raw bodies and credential generations stay
 * inside the owner.
 */
public final class InboundContracts {
    private InboundContracts() {}

    public record Query(String status, String eventType, int page, int size) {}

    public record Event(
            String eventNo,
            String connectionNo,
            String type,
            String status,
            String providerEventRef,
            Instant occurredAt,
            long attemptNo,
            String resultRef,
            long version) {}

    public record Receipt(
            String receiptNo,
            String eventNo,
            String classification,
            String signatureResult,
            String payloadHash,
            Instant receivedAt) {}

    public record Recovery(
            @JsonDeserialize(using = CommerceVersionDeserializer.class) Long version,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String reason) {
        @JsonAnySetter
        public void rejectUnknown(String key, Object value) {
            throw new IllegalArgumentException("Unknown callback recovery field");
        }
    }
}
