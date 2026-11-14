package ai.neargo.shop.food.server.api;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.food.config.ConditionalOnFoodEnabled;
import ai.neargo.shop.food.config.FoodPersistenceConfiguration.PersistenceEnabled;
import ai.neargo.shop.food.dto.DiningPickupContracts.PickupVerificationVO;
import ai.neargo.shop.food.service.DiningPickupVerificationService;
import ai.neargo.shop.food.spi.DiningPickupVerificationInternalApi;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Carries authenticated FOOD scope to original pickup verification without enclosing the remote owner in a transaction. */
@RestController
@Validated
@ConditionalOnFoodEnabled
@Conditional(PersistenceEnabled.class)
public class InternalDiningPickupVerificationController {
    private final DiningPickupVerificationService service;
    public InternalDiningPickupVerificationController(DiningPickupVerificationService service) { this.service=service; }
    @PostMapping(DiningPickupVerificationInternalApi.GET_PATH)
    public PickupVerificationVO get(@Valid @RequestBody DiningPickupVerificationInternalApi.Query c) {
        return service.get(c.entityNo(),c.storeNo(),c.actorNo(),c.batchNo(),c.idempotencyKey());
    }
    @PostMapping(DiningPickupVerificationInternalApi.VERIFY_PATH)
    public PickupVerificationVO verify(@Valid @RequestBody DiningPickupVerificationInternalApi.Command c) {
        return service.verify(c.entityNo(),c.storeNo(),c.actorNo(),c.batchNo(),c.definition(),c.idempotencyKey());
    }
    @ExceptionHandler(BizException.class)
    public ResponseEntity<Void> businessFailure(BizException failure) {
        int status=switch(failure.errorCode()) {
            case BAD_REQUEST -> 400;
            case FORBIDDEN -> 403;
            case NOT_FOUND -> 404;
            case CONFLICT -> 409;
            case PRICING_OWNER_UNSUPPORTED, PAY_MODE_NOT_SUPPORTED -> 503;
            default -> 500;
        };
        return ResponseEntity.status(status).build();
    }
}
