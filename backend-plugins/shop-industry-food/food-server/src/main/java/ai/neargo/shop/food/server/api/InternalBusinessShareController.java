package ai.neargo.shop.food.server.api;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.food.config.ConditionalOnFoodEnabled;
import ai.neargo.shop.food.config.FoodPersistenceConfiguration.PersistenceEnabled;
import ai.neargo.shop.food.dto.BusinessShareContracts.PlanVO;
import ai.neargo.shop.food.service.BusinessShareService;
import ai.neargo.shop.food.spi.BusinessShareInternalApi;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Internal business-only AA preserves the same source validation in both deployment forms. */
@RestController
@Validated
@ConditionalOnFoodEnabled
@Conditional(PersistenceEnabled.class)
public class InternalBusinessShareController {
    private final BusinessShareService service;
    public InternalBusinessShareController(BusinessShareService service) { this.service=service; }
    @PostMapping(BusinessShareInternalApi.CREATE)
    public PlanVO create(@Valid @RequestBody BusinessShareInternalApi.CreateCommand command) {
        return service.create(command.entityNo(),command.storeNo(),command.actorNo(),command.checkNo(),
                command.definition(),command.idempotencyKey());
    }
    @PostMapping(BusinessShareInternalApi.GET)
    public PlanVO get(@Valid @RequestBody BusinessShareInternalApi.Lookup query) {
        return service.get(query.entityNo(),query.storeNo(),query.checkNo(),query.planNo());
    }
    @PostMapping(BusinessShareInternalApi.CONFIRM)
    public PlanVO confirm(@Valid @RequestBody BusinessShareInternalApi.ConfirmCommand command) {
        return service.confirm(command.entityNo(),command.storeNo(),command.actorNo(),command.checkNo(),
                command.planNo(),command.definition(),command.idempotencyKey());
    }
    @PostMapping(BusinessShareInternalApi.CANCEL)
    public PlanVO cancel(@Valid @RequestBody BusinessShareInternalApi.CancelCommand command) {
        return service.cancel(command.entityNo(),command.storeNo(),command.actorNo(),command.checkNo(),
                command.planNo(),command.definition(),command.idempotencyKey());
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
