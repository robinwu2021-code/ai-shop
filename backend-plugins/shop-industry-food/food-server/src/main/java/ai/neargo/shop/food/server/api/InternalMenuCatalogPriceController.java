package ai.neargo.shop.food.server.api;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.food.config.ConditionalOnFoodEnabled;
import ai.neargo.shop.food.config.FoodPersistenceConfiguration.PersistenceEnabled;
import ai.neargo.shop.food.dto.MenuCatalogPriceContracts.Facts;
import ai.neargo.shop.food.dto.MenuCatalogPriceContracts.Query;
import ai.neargo.shop.food.policy.MenuCatalogPricePolicy;
import ai.neargo.shop.food.service.MenuCatalogPriceService;
import ai.neargo.shop.food.spi.MenuCatalogPriceInternalApi;

import org.springframework.context.annotation.Conditional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Existing FOOD security authenticates first; only original scope may be requested, not financial
 * facts.
 */
@RestController
@ConditionalOnFoodEnabled
@Conditional(PersistenceEnabled.class)
public class InternalMenuCatalogPriceController {
    private final MenuCatalogPriceService service;

    public InternalMenuCatalogPriceController(MenuCatalogPriceService service) {
        this.service = service;
    }

    @PostMapping(MenuCatalogPriceInternalApi.CAPTURE_PATH)
    public Facts capture(@RequestBody Query query) {
        MenuCatalogPricePolicy.validateQuery(query);
        return service.capture(query);
    }

    @ExceptionHandler(BizException.class)
    public ResponseEntity<Void> businessFailure(BizException failure) {
        int status =
                switch (failure.errorCode()) {
                    case BAD_REQUEST -> 400;
                    case FORBIDDEN -> 403;
                    case NOT_FOUND -> 404;
                    case CONFLICT -> 409;
                    case PRICING_OWNER_UNSUPPORTED -> 503;
                    default -> 500;
                };
        return ResponseEntity.status(status).build();
    }
}
