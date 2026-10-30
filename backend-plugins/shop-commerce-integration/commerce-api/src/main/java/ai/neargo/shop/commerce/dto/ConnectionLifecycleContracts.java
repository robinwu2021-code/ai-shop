package ai.neargo.shop.commerce.dto;

import ai.neargo.shop.spi.product.CatalogRevisionDeserializer;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * Only configuration and intended state are accepted; callers never submit identity verification
 * results.
 */
public final class ConnectionLifecycleContracts {
    private ConnectionLifecycleContracts() {}

    public record Configuration(
            @NotNull @JsonDeserialize(using = CatalogRevisionDeserializer.class) Long version,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String acceptMode,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String fulfillmentProfile,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String sourceRoutingRef,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String catalogAuthority) {
        @JsonAnySetter
        public void rejectUnknown(String key, Object value) {
            throw unknown();
        }
    }

    public record Verify(
            @NotNull @JsonDeserialize(using = CatalogRevisionDeserializer.class) Long version) {
        @JsonAnySetter
        public void rejectUnknown(String key, Object value) {
            throw unknown();
        }
    }

    public record SetStatus(
            @NotNull @JsonDeserialize(using = CatalogRevisionDeserializer.class) Long version,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String status,
            @Size(max = 256) @JsonDeserialize(using = CommerceTextDeserializer.class)
                    String reason) {
        @JsonAnySetter
        public void rejectUnknown(String key, Object value) {
            throw unknown();
        }
    }

    private static IllegalArgumentException unknown() {
        return new IllegalArgumentException("Unknown connection field");
    }
}
