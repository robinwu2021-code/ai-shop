package ai.neargo.shop.food.server.api;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.food.config.ConditionalOnFoodEnabled;
import ai.neargo.shop.food.config.FoodPersistenceConfiguration.PersistenceEnabled;
import ai.neargo.shop.food.dto.DiningSessionTableVO;
import ai.neargo.shop.food.dto.DiningSessionVO;
import ai.neargo.shop.food.dto.OperationResultVO;
import ai.neargo.shop.food.dto.TableTokenVO;
import ai.neargo.shop.food.service.DiningSessionService;
import ai.neargo.shop.food.spi.DiningSessionInternalApi;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Typed food transport accepts only the existing internal service authentication. */
@RestController
@Validated
@ConditionalOnFoodEnabled
@Conditional(PersistenceEnabled.class)
public class InternalDiningSessionController {
    private final DiningSessionService service;
    public InternalDiningSessionController(DiningSessionService service) { this.service=service; }
    @PostMapping(DiningSessionInternalApi.ROTATE_PATH)
    public TableTokenVO rotate(@Valid @RequestBody DiningSessionInternalApi.RotateCommand c) {
        return service.rotateTableToken(c.entityNo(),c.storeNo(),c.tableNo(),c.actorNo(),c.request(),c.idempotencyKey());
    }
    @PostMapping(DiningSessionInternalApi.REVOKE_PATH)
    public OperationResultVO revoke(@Valid @RequestBody DiningSessionInternalApi.RevokeCommand c) {
        return service.revokeTableToken(c.entityNo(),c.storeNo(),c.tableNo(),c.actorNo(),c.request(),c.idempotencyKey());
    }
    @PostMapping(DiningSessionInternalApi.RESOLVE_PATH)
    public DiningSessionTableVO resolve(@Valid @RequestBody DiningSessionInternalApi.ResolveCommand c) {
        return service.resolveTableToken(c.tableToken());
    }
    @PostMapping(DiningSessionInternalApi.START_PATH)
    public DiningSessionVO start(@Valid @RequestBody DiningSessionInternalApi.StartCommand c) {
        return service.startSession(c.entityNo(),c.storeNo(),c.buyerNo(),c.request(),c.idempotencyKey());
    }
    @PostMapping(DiningSessionInternalApi.END_PATH)
    public OperationResultVO end(@Valid @RequestBody DiningSessionInternalApi.EndCommand c) {
        return service.endSession(c.sessionNo(),c.accessToken(),c.buyerNo(),c.idempotencyKey());
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
