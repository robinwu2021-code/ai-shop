package ai.neargo.shop.food.server.api;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.config.ConditionalOnFoodEnabled;
import ai.neargo.shop.food.config.FoodPersistenceConfiguration.PersistenceEnabled;
import ai.neargo.shop.food.service.SalesRestrictionService;
import ai.neargo.shop.food.spi.SalesRestrictionInternalApi;
import ai.neargo.shop.food.dto.SalesRestrictionVO;
import ai.neargo.shop.food.dto.SalesRestrictionEvaluationVO;
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
public class InternalSalesRestrictionController {
    private final SalesRestrictionService service;
    public InternalSalesRestrictionController(SalesRestrictionService service) { this.service = service; }

    @PostMapping(SalesRestrictionInternalApi.MANAGE_PATH)
    public SalesRestrictionVO manage(@Valid @RequestBody SalesRestrictionInternalApi.Command c) {
        return service.manage(c.entityNo(), c.storeNo(), c.actorNo(), c.definition(), c.idempotencyKey());
    }
    @PostMapping(SalesRestrictionInternalApi.LIST_PATH)
    public PageData<SalesRestrictionVO> list(@Valid @RequestBody SalesRestrictionInternalApi.Lookup c) {
        return service.list(c.entityNo(), c.storeNo(), c.query());
    }
    @PostMapping(SalesRestrictionInternalApi.EVALUATE_PATH)
    public SalesRestrictionEvaluationVO evaluate(@Valid @RequestBody SalesRestrictionInternalApi.Evaluation c) {
        return service.evaluate(c.entityNo(), c.storeNo(), c.skuNo(), c.orderSource(), c.fulfillment());
    }

    /** Unexpected provider failures must not become a successful public API envelope. */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Void> infrastructureFailure(RuntimeException failure) {
        return ResponseEntity.internalServerError().build();
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
