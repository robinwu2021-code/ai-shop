package ai.neargo.shop.food.server.api;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.food.config.ConditionalOnFoodEnabled;
import ai.neargo.shop.food.config.FoodPersistenceConfiguration.PersistenceEnabled;
import ai.neargo.shop.food.dto.StoreCalendarSourceContracts.Facts;
import ai.neargo.shop.food.dto.StoreCalendarSourceContracts.Query;
import ai.neargo.shop.food.policy.StoreCalendarSourcePolicy;
import ai.neargo.shop.food.service.StoreCalendarSourceService;
import ai.neargo.shop.food.spi.StoreCalendarSourceInternalApi;

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
public class InternalStoreCalendarSourceController {
    private final StoreCalendarSourceService service;

    public InternalStoreCalendarSourceController(StoreCalendarSourceService service) {
        this.service = service;
    }

    @PostMapping(StoreCalendarSourceInternalApi.CAPTURE_PATH)
    public Facts capture(@RequestBody Query query) {
        StoreCalendarSourcePolicy.validateQuery(query);
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
                    default -> 500;
                };
        return ResponseEntity.status(status).build();
    }
}
