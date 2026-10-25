package ai.neargo.shop.food.dto;

import ai.neargo.shop.spi.product.CatalogRevisionDeserializer;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import tools.jackson.databind.annotation.JsonDeserialize;

import java.time.Instant;
import java.util.List;

/**
 * Restricted restaurant parties and device credentials, independent of platform accounts and
 * payment authority.
 */
public final class DiningIdentityContracts {
    private DiningIdentityContracts() {}

    public record RegisterDeviceReq(
            @NotBlank @Size(max = 32) String deviceNo,
            @JsonDeserialize(using = CatalogRevisionDeserializer.class) @NotNull Long version,
            @NotBlank @Size(max = 64) String displayName) {
        @JsonAnySetter
        public void unknown(String name, Object value) {
            throw new IllegalArgumentException(name);
        }
    }

    public record VersionReq(
            @JsonDeserialize(using = CatalogRevisionDeserializer.class) @NotNull Long version,
            @NotBlank @Size(max = 255) String reason) {
        @JsonAnySetter
        public void unknown(String name, Object value) {
            throw new IllegalArgumentException(name);
        }
    }

    public record DeviceVO(
            String entityNo,
            String storeNo,
            String deviceNo,
            String displayName,
            String status,
            long generation,
            long version) {}

    /**
     * rawCredential is present only on the first successful issuance, never in persisted operation
     * receipts.
     */
    public record DeviceCredentialVO(DeviceVO device, String rawCredential) {
        @Override
        public String toString() {
            return "DeviceCredentialVO[device=" + device + ",rawCredential=REDACTED]";
        }
    }

    public record StaffPartyReq(
            @Size(max = 32) String checkNo,
            @NotBlank @Size(max = 64) String displayName,
            @Min(60) @Max(86400) int lifetimeSeconds) {
        @JsonAnySetter
        public void unknown(String name, Object value) {
            throw new IllegalArgumentException(name);
        }
    }

    public record DeviceSessionReq(@Min(60) @Max(86400) int lifetimeSeconds) {
        @JsonAnySetter
        public void unknown(String name, Object value) {
            throw new IllegalArgumentException(name);
        }
    }

    public record PartyVO(
            String kind,
            String entityNo,
            String storeNo,
            String sessionNo,
            String deviceNo,
            String displayName,
            Instant expiresAt,
            List<String> scope,
            String checkNo,
            String buyerNo,
            long deviceGeneration,
            long version) {
        public PartyVO {
            scope = List.copyOf(scope);
        }
    }

    /**
     * accessToken is a one-time issuance result. Replaying the same command returns the original
     * party with null token.
     */
    public record SessionCredentialVO(PartyVO party, String accessToken) {
        @Override
        public String toString() {
            return "SessionCredentialVO[party=" + party + ",accessToken=REDACTED]";
        }
    }
}
