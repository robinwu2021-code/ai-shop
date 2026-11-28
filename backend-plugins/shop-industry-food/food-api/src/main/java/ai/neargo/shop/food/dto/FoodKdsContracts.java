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

    /**
     * 事件打印的来源：由原始业务事件（接单、厨房动作、改单、账单变化）投影出的一份内容，打印时据此定格单据。
     * 不再落厨显事件表（TDD-餐饮域迁移新库 §2.8 D-3）。Payload is a selected business projection, never raw
     * order/customer rows or credentials.
     */
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
}
