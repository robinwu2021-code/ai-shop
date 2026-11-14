package ai.neargo.shop.food.server.api;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.food.config.ConditionalOnFoodEnabled;
import ai.neargo.shop.food.config.FoodPersistenceConfiguration.PersistenceEnabled;
import ai.neargo.shop.food.dto.KitchenReturnContracts.HandledVO;
import ai.neargo.shop.food.dto.KitchenReturnContracts.ReturnKitchenVO;
import ai.neargo.shop.food.service.KitchenReturnService;
import ai.neargo.shop.food.spi.KitchenReturnInternalApi;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Uses one local atomic kitchen disposition service in both deployment modes. */
@RestController
@Validated
@ConditionalOnFoodEnabled
@Conditional(PersistenceEnabled.class)
public class InternalKitchenReturnController {
    private final KitchenReturnService service;
    public InternalKitchenReturnController(KitchenReturnService service) { this.service=service; }
    @PostMapping(KitchenReturnInternalApi.GET_PATH)
    public ReturnKitchenVO get(@Valid @RequestBody KitchenReturnInternalApi.Query c) {
        return service.get(c.entityNo(),c.storeNo(),c.returnNo());
    }
    @PostMapping(KitchenReturnInternalApi.HANDLE_PATH)
    public HandledVO handle(@Valid @RequestBody KitchenReturnInternalApi.Command c) {
        return service.handle(c.entityNo(),c.storeNo(),c.actorNo(),c.returnNo(),c.definition(),c.idempotencyKey());
    }
    @ExceptionHandler(BizException.class)
    public ResponseEntity<Void> businessFailure(BizException failure) {
        int status=switch(failure.errorCode()) {
            case BAD_REQUEST -> 400;
            case FORBIDDEN -> 403;
            case NOT_FOUND -> 404;
            case CONFLICT -> 409;
            default -> 500;
        };
        return ResponseEntity.status(status).build();
    }
}
