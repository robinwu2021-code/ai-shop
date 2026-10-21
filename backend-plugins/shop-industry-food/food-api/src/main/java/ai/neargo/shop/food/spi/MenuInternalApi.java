package ai.neargo.shop.food.spi;

import ai.neargo.shop.food.dto.CreateMenuReq;
import ai.neargo.shop.food.dto.CreateMenuResultVO;
import ai.neargo.shop.food.dto.MenuConfigResultVO;
import ai.neargo.shop.food.dto.ListMenusQuery;
import ai.neargo.shop.food.dto.MenuResultVO;
import ai.neargo.shop.food.dto.MenuVO;
import ai.neargo.shop.food.dto.UpdateMenuReq;
import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import ai.neargo.shop.spi.product.CatalogRevisionDeserializer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Only authenticated platform scope crosses the original FOOD service-token transport. */
public interface MenuInternalApi {
    String CREATE_PATH = "/internal/food/menus/create";
    String UPDATE_PATH = "/internal/food/menus/update";
    String GET_PATH = "/internal/food/menus/get";
    String LIST_PATH = "/internal/food/menus/list";

    @PostExchange(CREATE_PATH)
    CreateMenuResultVO createMenu(@RequestBody CreateCommand command);
    @PostExchange(UPDATE_PATH)
    MenuResultVO updateMenu(@RequestBody UpdateCommand command);
    @PostExchange(GET_PATH)
    MenuConfigResultVO getMenu(@RequestBody GetQuery query);
    @PostExchange(LIST_PATH)
    PageReceipt listMenus(@RequestBody Lookup query);

    /** Same public page shape, with transport-local strict counters before receipt validation. */
    record PageReceipt(List<MenuVO> records,
            @JsonDeserialize(using=CatalogRevisionDeserializer.class) Long total,
            @JsonDeserialize(using=CatalogRevisionDeserializer.class) Long page,
            @JsonDeserialize(using=CatalogRevisionDeserializer.class) Long size) { }

    record CreateCommand(@NotBlank @Size(max=32)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String entityNo,
            @NotBlank @Size(max=32)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo,
            @NotBlank @Size(max=32)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String actorNo,
            @NotBlank @Size(max=128)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String idempotencyKey,
            @NotNull @Valid CreateMenuReq definition) { }
    record UpdateCommand(@NotBlank @Size(max=32)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String entityNo,
            @NotBlank @Size(max=32)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo,
            @NotBlank @Size(max=32)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String menuNo,
            @NotBlank @Size(max=32)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String actorNo,
            @NotBlank @Size(max=128)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String idempotencyKey,
            @NotNull @Valid UpdateMenuReq definition) { }
    record GetQuery(@NotBlank @Size(max=32)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String entityNo,
            @NotBlank @Size(max=32)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo,
            @NotBlank @Size(max=32)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String menuNo) { }
    record Lookup(@NotBlank @Size(max=32)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String entityNo,
            @NotBlank @Size(max=32)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo,
            @Valid ListMenusQuery query) { }
}
