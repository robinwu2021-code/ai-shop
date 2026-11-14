package ai.neargo.shop.food.server.api;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.food.config.ConditionalOnFoodEnabled;
import ai.neargo.shop.food.config.FoodPersistenceConfiguration.PersistenceEnabled;
import ai.neargo.shop.food.dto.DiningPriceFacts;
import ai.neargo.shop.food.service.DiningPricingFactsService;
import ai.neargo.shop.food.spi.PricingSourceInternalApi;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Shared internal authentication runs before request parsing; original cart credentials still apply. */
@RestController
@Validated
@ConditionalOnFoodEnabled
@Conditional(PersistenceEnabled.class)
public class InternalPricingSourceController {
    private final DiningPricingFactsService service;
    public InternalPricingSourceController(DiningPricingFactsService service) { this.service = service; }
    @PostMapping(PricingSourceInternalApi.STAFF_PATH)
    public DiningPriceFacts staff(@Valid @RequestBody PricingSourceInternalApi.StaffCommand c) {
        return service.staff(c.entityNo(), c.storeNo(), c.cartNo(), c.expectedVersion());
    }
    @PostMapping(PricingSourceInternalApi.CUSTOMER_PATH)
    public DiningPriceFacts customer(@Valid @RequestBody PricingSourceInternalApi.CustomerCommand c) {
        return service.customer(c.cartNo(), c.access(), c.expectedVersion());
    }
    @ExceptionHandler(BizException.class)
    public ResponseEntity<Void> businessFailure(BizException failure) {
        int status = switch (failure.errorCode()) {
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
