package ai.neargo.shop.food.dto;

import ai.neargo.shop.spi.product.CatalogRevisionDeserializer;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import tools.jackson.databind.annotation.JsonDeserialize;

import java.util.List;

/**
 * Authenticated actor plus a live bound device grant; identifiers alone never authorize a
 * subscription.
 */
public final class FoodKdsContracts {
    private FoodKdsContracts() {}

    public record GrantReq(
            @NotBlank @Size(max = 32) String deviceNo,
            @NotBlank @Size(max = 32) String deviceActorNo,
            @NotEmpty @Size(max = 100) List<@NotBlank @Size(max = 32) String> stationNos,
            @Min(60) @Max(86400) int lifetimeSeconds) {
        public GrantReq {
            if (stationNos != null) {
                stationNos = List.copyOf(stationNos);
            }
        }

        @JsonAnySetter
        public void unknown(String name, Object value) {
            throw new IllegalArgumentException(name);
        }
    }

    public record RevokeReq(
            @JsonProperty(value = "version", required = true)
                    @JsonDeserialize(using = CatalogRevisionDeserializer.class)
                    @Min(0)
                    long version,
            @NotBlank @Size(max = 255) String reason) {
        @JsonAnySetter
        public void unknown(String name, Object value) {
            throw new IllegalArgumentException(name);
        }
    }

    public record SubscriptionVO(
            String subscriptionNo,
            String entityNo,
            String storeNo,
            String deviceNo,
            String actorNo,
            List<String> stationNos,
            String expiresAt,
            String status,
            long version) {
        public SubscriptionVO {
            stationNos = List.copyOf(stationNos);
        }
    }

    /** Payload is a selected business projection, never raw order/customer rows or credentials. */
    public record EventDraft(
            String eventNo,
            String entityNo,
            String storeNo,
            String stationNo,
            String eventType,
            String sourceNo,
            long sourceVersion,
            String payload,
            String occurredAt) {}

    public record EventVO(
            long cursor,
            String eventNo,
            String stationNo,
            String eventType,
            String sourceNo,
            long sourceVersion,
            String payload,
            String occurredAt) {}

    public record ReplayVO(
            String subscriptionNo,
            String entityNo,
            String storeNo,
            List<EventVO> events,
            long nextCursor,
            long watermark,
            boolean hasMore) {
        public ReplayVO {
            events = List.copyOf(events);
        }
    }
}
