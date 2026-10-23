package ai.neargo.shop.food.server.api;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.food.config.ConditionalOnFoodEnabled;
import ai.neargo.shop.food.config.FoodPersistenceConfiguration.PersistenceEnabled;
import ai.neargo.shop.food.dto.GetPolicyQuery;
import ai.neargo.shop.food.dto.OperationVO;
import ai.neargo.shop.food.dto.StoreOperationPolicyResultVO;
import ai.neargo.shop.food.service.StoreOperationService;
import ai.neargo.shop.food.spi.StoreOperationInternalApi;
import ai.neargo.shop.food.spi.StorePolicyPublicationInternalApi;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Trusted platform-only policy read endpoint; the existing internal security chain validates the service token. */
@RestController
@Validated
@ConditionalOnFoodEnabled
@Conditional(PersistenceEnabled.class)
public class InternalStoreOperationPolicyController {
    private final StoreOperationService service;

    /** Uses the local restaurant Service; identity and store predicates are revalidated in its query. */
    public InternalStoreOperationPolicyController(StoreOperationService service) {
        this.service = service;
    }

    /** Internal responses deliberately follow the typed contract without a public API envelope. */
    @PostMapping(StoreOperationInternalApi.POLICY_PATH)
    public StoreOperationPolicyResultVO getPolicy(@Valid @RequestBody StoreOperationInternalApi.Lookup request) {
        return service.getPolicy(request.entityNo(), request.storeNo(), new GetPolicyQuery(request.at()));
    }

    /** The service token authenticates the platform; required actor/scope coordinates remain explicit. */
    @PostMapping(StorePolicyPublicationInternalApi.POLICY_CREATE_PATH)
    public OperationVO createPolicy(@Valid @RequestBody StorePolicyPublicationInternalApi.Command command) {
        return service.createPolicy(command.entityNo(), command.storeNo(), command.actorNo(), command.policy(),
                command.idempotencyKey());
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
