package ai.neargo.shop.food.dto;

import ai.neargo.shop.spi.product.CatalogRevisionDeserializer;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * Keeps the FoodStationRuntimeContracts business boundary scoped to original restaurant facts and
 * lifecycle authority.
 */
public final class FoodStationRuntimeContracts {
    private FoodStationRuntimeContracts() {}

    public record ControlReq(
            @JsonProperty(value = "version", required = true)
                    @JsonDeserialize(using = CatalogRevisionDeserializer.class)
                    @Min(0)
                    long version,
            @NotBlank String action,
            @NotBlank @Size(max = 255) String reason,
            @Size(max = 32) String deviceNo,
            @Size(max = 128) String voiceText) {
        @JsonAnySetter
        public void unknown(String name, Object value) {
            throw new IllegalArgumentException(name);
        }
    }

    public record StateVO(String stationNo, String status, long version) {}

    public record CommandVO(
            String commandNo,
            String stationNo,
            String action,
            String status,
            long sourceVersion,
            String reason) {}

    public record VoiceClaim(
            String commandNo,
            String stationNo,
            String deviceNo,
            String voiceText,
            String leaseToken,
            String leaseUntil) {}

    public record VoiceReceipt(
            @NotBlank @Size(max = 32) String commandNo,
            @NotBlank @Size(max = 64) String leaseToken,
            @NotBlank String status,
            @Size(max = 64) String responseCode) {
        @JsonAnySetter
        public void unknown(String name, Object value) {
            throw new IllegalArgumentException(name);
        }
    }

    public record VoicePoll(VoiceClaim claim) {}
}
