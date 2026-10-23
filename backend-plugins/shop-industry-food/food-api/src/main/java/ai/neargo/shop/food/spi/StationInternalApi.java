package ai.neargo.shop.food.spi;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.dto.OperationVO;
import ai.neargo.shop.food.dto.StationContracts.CreateStationReq;
import ai.neargo.shop.food.dto.StationContracts.GetStationVO;
import ai.neargo.shop.food.dto.StationContracts.ReplaceStationRoutesReq;
import ai.neargo.shop.food.dto.StationContracts.StationMutationVO;
import ai.neargo.shop.food.dto.StationContracts.StationRoutesVO;
import ai.neargo.shop.food.dto.StationContracts.StationVO;
import ai.neargo.shop.food.dto.StationContracts.UpdateStationReq;
import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import ai.neargo.shop.food.policy.MenuDefinitionPolicy;
import tools.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/** Authenticated platform scope is transported through the original FOOD service token boundary. */
public interface StationInternalApi {
    String CREATE_PATH="/internal/food/stations/create";
    String UPDATE_PATH="/internal/food/stations/update";
    String GET_PATH="/internal/food/stations/get";
    String LIST_PATH="/internal/food/stations/list";
    String ROUTES_PATH="/internal/food/stations/replace-routes";
    String GET_ROUTES_PATH="/internal/food/stations/get-routes";
    @PostExchange(CREATE_PATH) StationMutationVO createStation(@RequestBody CreateCommand command);
    @PostExchange(UPDATE_PATH) StationMutationVO updateStation(@RequestBody UpdateCommand command);
    @PostExchange(GET_PATH) GetStationVO getStation(@RequestBody GetQuery query);
    @PostExchange(LIST_PATH) PageData<StationVO> listStations(@RequestBody Lookup query);
    @PostExchange(ROUTES_PATH) OperationVO replaceRoutes(@RequestBody RoutesCommand command);
    @PostExchange(GET_ROUTES_PATH) StationRoutesVO getRoutes(@RequestBody GetQuery query);
    record CreateCommand(@NotBlank @Size(max=32)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String entityNo,
            @NotBlank @Size(max=32)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo,
            @NotBlank @Size(max=32)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String actorNo,
            @NotBlank @Size(max=128)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String idempotencyKey,
            @NotNull @Valid CreateStationReq definition) { }
    record UpdateCommand(@NotBlank @Size(max=32)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String entityNo,
            @NotBlank @Size(max=32)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo,
            @NotBlank @Size(max=32)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String stationNo,
            @NotBlank @Size(max=32)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String actorNo,
            @NotBlank @Size(max=128)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String idempotencyKey,
            @NotNull @Valid UpdateStationReq definition) { }
    record RoutesCommand(@NotBlank @Size(max=32)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String entityNo,
            @NotBlank @Size(max=32)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo,
            @NotBlank @Size(max=32)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String skuNo,
            @NotBlank @Size(max=32)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String actorNo,
            @NotBlank @Size(max=128)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String idempotencyKey,
            @NotNull @Valid ReplaceStationRoutesReq definition) { }
    record GetQuery(@NotBlank @Size(max=32)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String entityNo,
            @NotBlank @Size(max=32)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo,
            @NotBlank @Size(max=32)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String configNo) { }
    record Lookup(@NotBlank @Size(max=32)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String entityNo,
            @NotBlank @Size(max=32)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo,
            @JsonDeserialize(using=MenuDefinitionPolicy.IntegerDeserializer.class) Integer page,
            @JsonDeserialize(using=MenuDefinitionPolicy.IntegerDeserializer.class) Integer size) { }
}
