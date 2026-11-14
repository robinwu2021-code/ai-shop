package ai.neargo.shop.food.server.api;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.food.config.ConditionalOnFoodEnabled;
import ai.neargo.shop.food.config.FoodPersistenceConfiguration.PersistenceEnabled;
import ai.neargo.shop.food.dto.BusinessReturnContracts.ReturnVO;
import ai.neargo.shop.food.service.BusinessReturnService;
import ai.neargo.shop.food.spi.BusinessReturnInternalApi;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Separate return source types prevent local business requests from entering the financial after-sale protocol. */
@RestController
@Validated
@ConditionalOnFoodEnabled
@Conditional(PersistenceEnabled.class)
public class InternalBusinessReturnController {
    private final BusinessReturnService service;
    public InternalBusinessReturnController(BusinessReturnService service) { this.service=service; }
    @PostMapping(BusinessReturnInternalApi.CREATE)
    public ReturnVO create(@Valid @RequestBody BusinessReturnInternalApi.CreateCommand command) {
        return service.create(command.entityNo(),command.storeNo(),command.actorNo(),command.batchNo(),
                command.definition(),command.idempotencyKey());
    }
    @PostMapping(BusinessReturnInternalApi.GET)
    public ReturnVO get(@Valid @RequestBody BusinessReturnInternalApi.Lookup query) {
        return service.get(query.entityNo(),query.storeNo(),query.returnNo());
    }
    @PostMapping(BusinessReturnInternalApi.CANCEL)
    public ReturnVO cancel(@Valid @RequestBody BusinessReturnInternalApi.CancelCommand command) {
        return service.cancel(command.entityNo(),command.storeNo(),command.actorNo(),command.returnNo(),
                command.definition(),command.idempotencyKey());
    }
    @ExceptionHandler(BizException.class)
    public ResponseEntity<Void> businessFailure(BizException failure) {
        int status=switch(failure.errorCode()) {
            case BAD_REQUEST -> 400;
            case FORBIDDEN -> 403;
            case NOT_FOUND -> 404;
            case CONFLICT -> 409;
            case PRICING_OWNER_UNSUPPORTED -> 422;
            default -> 500;
        };
        return ResponseEntity.status(status).build();
    }
}
