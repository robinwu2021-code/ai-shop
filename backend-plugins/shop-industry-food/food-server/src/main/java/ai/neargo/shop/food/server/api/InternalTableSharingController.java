package ai.neargo.shop.food.server.api;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.food.config.ConditionalOnFoodEnabled;
import ai.neargo.shop.food.config.FoodPersistenceConfiguration.PersistenceEnabled;
import ai.neargo.shop.food.dto.CheckResultVO;
import ai.neargo.shop.food.service.CheckService;
import ai.neargo.shop.food.spi.CheckInternalApi;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Service-token-only transport; no consumer/merchant session endpoints in the independent server. */
@RestController
@Validated
@ConditionalOnFoodEnabled
@Conditional(PersistenceEnabled.class)
public class InternalTableSharingController {
    private final CheckService service;
    public InternalTableSharingController(CheckService service) { this.service=service; }
    @PostMapping(CheckInternalApi.SHARE_PATH)
    public CheckResultVO share(@Valid @RequestBody CheckInternalApi.ShareCommand c) {
        return service.openSharedCheck(c.entityNo(),c.storeNo(),c.tableNo(),c.actorNo(),c.request(),c.idempotencyKey());
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
