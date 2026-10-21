package ai.neargo.shop.food.spi;

import ai.neargo.shop.food.dto.DishResultVO;
import ai.neargo.shop.food.dto.DishCatalogItemVO;
import ai.neargo.shop.food.dto.DishDetailResultVO;
import ai.neargo.shop.food.dto.ListDishesQuery;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.dto.SaveDishReq;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/** Carries platform-resolved ownership over the original service-token transport. */
public interface DishInternalApi {
    String SAVE_PATH = "/internal/food/dishes/save";

    @PostExchange(SAVE_PATH)
    DishResultVO saveDish(@RequestBody SaveCommand command);

    String LIST_PATH = "/internal/food/dishes/list";
    String GET_PATH = "/internal/food/dishes/get";

    @PostExchange(LIST_PATH)
    PageData<DishCatalogItemVO> listDishes(@RequestBody Lookup query);
    @PostExchange(GET_PATH)
    DishDetailResultVO getDish(@RequestBody Identity query);

    record Lookup(@NotBlank @Size(max=32) String entityNo, @NotBlank @Size(max=32) String storeNo,
            @Valid ListDishesQuery query) { }
    record Identity(@NotBlank @Size(max=32) String entityNo, @NotBlank @Size(max=32) String storeNo,
            @NotBlank @Size(max=32) String goodsNo) { }

    record SaveCommand(@NotBlank @Size(max=32) String entityNo, @NotBlank @Size(max=32) String storeNo,
            @NotBlank @Size(max=32) String goodsNo, @NotBlank @Size(max=32) String actorNo,
            @NotBlank @Size(max=128) String idempotencyKey, @Valid @NotNull SaveDishReq dish) { }
}
