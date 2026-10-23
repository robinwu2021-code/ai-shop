package ai.neargo.shop.food.dto;

import ai.neargo.shop.spi.product.CatalogRevisionDeserializer;
import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import ai.neargo.shop.food.policy.MenuDefinitionPolicy;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Store-scoped kitchen definitions never carry client ownership or immutable publication facts. */
public final class StationContracts {
    private StationContracts() { }
    public record CreateStationReq(
            @NotBlank @Size(max=64)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String name,
            @NotBlank @Size(max=24)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String stationType) {
        @JsonAnySetter public void rejectUnknown(String name, Object value) {
            throw new IllegalArgumentException("Unknown station field");
        }
    }
    public record UpdateStationReq(@NotNull @JsonDeserialize(using=CatalogRevisionDeserializer.class) Long version,
            @NotBlank @Size(max=64)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String name,
            @NotBlank @Size(max=24)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String stationType,
            @NotNull @JsonDeserialize(using=MenuDefinitionPolicy.BooleanDeserializer.class) Boolean enabled) {
        @JsonAnySetter public void rejectUnknown(String name, Object value) {
            throw new IllegalArgumentException("Unknown station field");
        }
    }
    public record StationRouteInput(@NotBlank @Size(max=32)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String stationNo,
            @NotBlank @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String taskRole) {
        @JsonAnySetter public void rejectUnknown(String name, Object value) {
            throw new IllegalArgumentException("Unknown station route field");
        }
    }
    public record ReplaceStationRoutesReq(
            @NotNull @JsonDeserialize(using=CatalogRevisionDeserializer.class) Long version,
            @NotNull @Size(max=100) List<@Valid StationRouteInput> routes) {
        @JsonAnySetter public void rejectUnknown(String name, Object value) {
            throw new IllegalArgumentException("Unknown station routes field");
        }
    }
    public record StationVO(@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String configNo,
            @JsonProperty(required=true) @JsonDeserialize(using=CatalogRevisionDeserializer.class) long version,
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String status,
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo,
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String name,
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String stationType,
            @JsonDeserialize(using=MenuDefinitionPolicy.BooleanDeserializer.class) boolean enabled,
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String publishedRevisionNo) { }
    public record StationMutationVO(@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String configNo,
            @JsonProperty(required=true) @JsonDeserialize(using=CatalogRevisionDeserializer.class) long version,
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String status) { }
    public record GetStationVO(StationVO config) { }
    public record StationRoutesVO(@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String skuNo,
            @JsonProperty(required=true) @JsonDeserialize(using=CatalogRevisionDeserializer.class) long version,
            List<StationRouteInput> routes) { }
}
