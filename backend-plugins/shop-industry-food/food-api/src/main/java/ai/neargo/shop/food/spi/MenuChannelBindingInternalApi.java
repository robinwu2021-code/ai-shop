package ai.neargo.shop.food.spi;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.dto.CreateMenuChannelBindingReq;
import ai.neargo.shop.food.dto.UpdateMenuChannelBindingReq;
import ai.neargo.shop.food.dto.MenuChannelBindingVO;
import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import ai.neargo.shop.food.policy.MenuDefinitionPolicy;
import tools.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/** Authenticated scope crosses the existing FOOD service-token boundary without external account credentials. */
public interface MenuChannelBindingInternalApi {
    String CREATE_PATH="/internal/food/menu-channel-bindings/create";
    String UPDATE_PATH="/internal/food/menu-channel-bindings/update";
    String GET_PATH="/internal/food/menu-channel-bindings/get";
    String LIST_PATH="/internal/food/menu-channel-bindings/list";
    @PostExchange(CREATE_PATH) MenuChannelBindingVO createBinding(@RequestBody CreateCommand command);
    @PostExchange(UPDATE_PATH) MenuChannelBindingVO updateBinding(@RequestBody UpdateCommand command);
    @PostExchange(GET_PATH) MenuChannelBindingVO getBinding(@RequestBody GetQuery query);
    @PostExchange(LIST_PATH) PageData<MenuChannelBindingVO> listBindings(@RequestBody Lookup query);
    record CreateCommand(@NotBlank @Size(max=32)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String entityNo,
            @NotBlank @Size(max=32)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo,
            @NotBlank @Size(max=32)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String actorNo,
            @NotBlank @Size(max=128)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String idempotencyKey,
            @NotNull @Valid CreateMenuChannelBindingReq definition) { }
    record UpdateCommand(@NotBlank @Size(max=32)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String entityNo,
            @NotBlank @Size(max=32)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo,
            @NotBlank @Size(max=32)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String bindingNo,
            @NotBlank @Size(max=32)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String actorNo,
            @NotBlank @Size(max=128)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String idempotencyKey,
            @NotNull @Valid UpdateMenuChannelBindingReq definition) { }
    record GetQuery(@NotBlank @Size(max=32)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String entityNo,
            @NotBlank @Size(max=32)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo,
            @NotBlank @Size(max=32)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String bindingNo) { }
    record Lookup(@NotBlank @Size(max=32)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String entityNo,
            @NotBlank @Size(max=32)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo,
            @JsonDeserialize(using=MenuDefinitionPolicy.IntegerDeserializer.class) Integer page,
            @JsonDeserialize(using=MenuDefinitionPolicy.IntegerDeserializer.class) Integer size,
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String provider,
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String menuNo) { }
}
