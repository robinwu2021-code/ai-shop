package ai.neargo.shop.food.server.api;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.config.ConditionalOnFoodEnabled;
import ai.neargo.shop.food.config.FoodPersistenceConfiguration.PersistenceEnabled;
import ai.neargo.shop.food.dto.ActionLogVO;
import ai.neargo.shop.food.dto.CheckDetailVO;
import ai.neargo.shop.food.dto.CheckResultVO;
import ai.neargo.shop.food.dto.CheckVO;
import ai.neargo.shop.food.dto.OperationResultVO;
import ai.neargo.shop.food.dto.OperationVO;
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
public class InternalCheckController {
    private final CheckService service;
    public InternalCheckController(CheckService service) { this.service=service; }
    @PostMapping(CheckInternalApi.OPEN_PATH)
    public CheckResultVO open(@Valid @RequestBody CheckInternalApi.OpenCommand c) {
        return service.openCheck(c.entityNo(),c.storeNo(),c.actorNo(),c.request(),c.idempotencyKey());
    }
    @PostMapping(CheckInternalApi.LIST_PATH)
    public PageData<CheckVO> list(@Valid @RequestBody CheckInternalApi.Lookup c) {
        return service.listChecks(c.entityNo(),c.storeNo(),c.query());
    }
    @PostMapping(CheckInternalApi.GET_PATH)
    public CheckDetailVO get(@Valid @RequestBody CheckInternalApi.Identity c) {
        return service.getCheck(c.entityNo(),c.storeNo(),c.checkNo());
    }
    @PostMapping(CheckInternalApi.SEATS_PATH)
    public CheckResultVO seats(@Valid @RequestBody CheckInternalApi.SeatsCommand c) {
        return service.saveSeats(c.entityNo(),c.storeNo(),c.checkNo(),c.actorNo(),c.request(),c.idempotencyKey());
    }
    @PostMapping(CheckInternalApi.CLEAR_PATH)
    public OperationVO clear(@Valid @RequestBody CheckInternalApi.ClearCommand c) {
        return service.clearCheck(c.entityNo(),c.storeNo(),c.checkNo(),c.actorNo(),c.request(),c.idempotencyKey());
    }
    @PostMapping(CheckInternalApi.TRANSFER_PATH)
    public OperationResultVO transfer(@Valid @RequestBody CheckInternalApi.TransferCommand c) {
        return service.transferCheck(c.entityNo(),c.storeNo(),c.checkNo(),c.actorNo(),c.request(),c.idempotencyKey());
    }
    @PostMapping(CheckInternalApi.COMBINE_PATH)
    public OperationResultVO combine(@Valid @RequestBody CheckInternalApi.CombineCommand c) {
        return service.combineTables(c.entityNo(),c.storeNo(),c.checkNo(),c.actorNo(),c.request(),c.idempotencyKey());
    }
    @PostMapping(CheckInternalApi.ACTIONS_PATH)
    public PageData<ActionLogVO> actions(@Valid @RequestBody CheckInternalApi.ActionsLookup c) {
        return service.listActions(c.entityNo(),c.storeNo(),c.checkNo(),c.query());
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
