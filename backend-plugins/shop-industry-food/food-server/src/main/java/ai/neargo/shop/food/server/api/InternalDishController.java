package ai.neargo.shop.food.server.api;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.dto.DishCatalogItemVO;
import ai.neargo.shop.food.dto.DishDetailResultVO;
import ai.neargo.shop.food.dto.ListDishesQuery;

import ai.neargo.shop.food.config.ConditionalOnFoodEnabled;
import ai.neargo.shop.food.config.FoodPersistenceConfiguration.PersistenceEnabled;
import ai.neargo.shop.food.dto.DishResultVO;
import ai.neargo.shop.food.service.DishService;
import ai.neargo.shop.food.spi.DishInternalApi;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Internal dish adapters use the original service token and one authoritative DishService. */
@RestController
@Validated
@ConditionalOnFoodEnabled
@Conditional(PersistenceEnabled.class)
public class InternalDishController {
    private final DishService service;
    public InternalDishController(DishService service) { this.service = service; }

    @PostMapping(DishInternalApi.SAVE_PATH)
    public DishResultVO saveDish(@Valid @RequestBody DishInternalApi.SaveCommand c) {
        return service.saveDish(c.entityNo(), c.storeNo(), c.goodsNo(), c.actorNo(), c.dish(), c.idempotencyKey());
    }

    @PostMapping(DishInternalApi.LIST_PATH)
    public PageData<DishCatalogItemVO> listDishes(@Valid @RequestBody DishInternalApi.Lookup query) {
        return service.listDishes(query.entityNo(), query.storeNo(), query.query());
    }
    @PostMapping(DishInternalApi.GET_PATH)
    public DishDetailResultVO getDish(@Valid @RequestBody DishInternalApi.Identity query) {
        return service.getDish(query.entityNo(), query.storeNo(), query.goodsNo());
    }

    /** Preserves business failures across the shared HTTP transport without returning query or row contents. */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<Void> businessFailure(BizException failure) {
        int status = switch (failure.errorCode()) {
            case BAD_REQUEST -> 400;
            case FORBIDDEN -> 403;
            case NOT_FOUND -> 404;
            case CONFLICT -> 409;
            default -> 500;
        };
        return ResponseEntity.status(status).build();
    }
}
