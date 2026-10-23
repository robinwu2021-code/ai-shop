package ai.neargo.shop.food.server.api;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.config.ConditionalOnFoodEnabled;
import ai.neargo.shop.food.config.FoodPersistenceConfiguration.PersistenceEnabled;
import ai.neargo.shop.food.dto.RecipeResultVO;
import ai.neargo.shop.food.dto.RecipeVO;
import ai.neargo.shop.food.service.RecipeService;
import ai.neargo.shop.food.spi.RecipeInternalApi;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Uses the existing service-token chain and the same service as embedded restaurant operations. */
@RestController
@Validated
@ConditionalOnFoodEnabled
@Conditional(PersistenceEnabled.class)
public class InternalRecipeController {
    private final RecipeService service;
    public InternalRecipeController(RecipeService service) { this.service = service; }

    @PostMapping(RecipeInternalApi.CREATE_PATH)
    public RecipeResultVO createRecipe(@Valid @RequestBody RecipeInternalApi.CreateCommand c) {
        return service.createRecipe(c.entityNo(), c.storeNo(), c.actorNo(), c.definition(), c.idempotencyKey());
    }
    @PostMapping(RecipeInternalApi.REPLACE_PATH)
    public RecipeResultVO replaceDefinition(@Valid @RequestBody RecipeInternalApi.UpdateCommand c) {
        return service.replaceDefinition(c.entityNo(), c.storeNo(), c.recipeNo(), c.actorNo(),
                c.definition(), c.idempotencyKey());
    }
    @PostMapping(RecipeInternalApi.GET_PATH)
    public RecipeResultVO getRecipe(@Valid @RequestBody RecipeInternalApi.GetQuery c) {
        return service.getRecipe(c.entityNo(), c.storeNo(), c.recipeNo());
    }
    @PostMapping(RecipeInternalApi.LIST_PATH)
    public PageData<RecipeVO> listRecipes(@Valid @RequestBody RecipeInternalApi.Lookup c) {
        return service.listRecipes(c.entityNo(), c.storeNo(), c.query());
    }

    /** Carries the existing business status through typed transport without leaking row contents. */
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
