package ai.neargo.shop.commerce.dto;

import ai.neargo.shop.spi.product.CatalogRevisionDeserializer;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import jakarta.validation.constraints.NotNull;

import tools.jackson.databind.annotation.JsonDeserialize;

import java.time.Instant;

/**
 * Public operation evidence excludes provider bodies, secrets, state digests and credential
 * references.
 */
public final class OperationContracts {
    private OperationContracts() {}

    public record Query(String type, String status, int page, int size) {}

    public record Schedule(
            @NotNull @JsonDeserialize(using = CatalogRevisionDeserializer.class) Long version,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String reason) {
        @JsonAnySetter
        public void rejectUnknown(String key, Object value) {
            throw new IllegalArgumentException("Unknown operation request field");
        }
    }

    public record Attempt(
            String attemptNo,
            long sequence,
            String kind,
            String outcome,
            String errorCode,
            String evidenceRef,
            Instant startedAt,
            Instant finishedAt) {}
}
