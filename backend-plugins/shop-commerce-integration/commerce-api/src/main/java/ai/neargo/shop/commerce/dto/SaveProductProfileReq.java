package ai.neargo.shop.commerce.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import jakarta.validation.constraints.NotNull;

import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * Protocol identity/capability changes require another installed profile; this public command only
 * controls its status.
 */
public record SaveProductProfileReq(
        @NotNull @JsonDeserialize(using = CommerceVersionDeserializer.class) Long version,
        @JsonDeserialize(using = CommerceTextDeserializer.class) String status,
        @JsonDeserialize(using = CommerceTextDeserializer.class) String reason) {
    @JsonAnySetter
    public void rejectUnknown(String key, Object value) {
        throw new IllegalArgumentException("Unknown product profile field");
    }
}
