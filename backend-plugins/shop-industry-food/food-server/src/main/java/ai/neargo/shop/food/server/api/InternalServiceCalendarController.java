package ai.neargo.shop.food.server.api;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.food.config.ConditionalOnFoodEnabled;
import ai.neargo.shop.food.config.FoodPersistenceConfiguration.PersistenceEnabled;
import ai.neargo.shop.food.dto.OperationVO;
import ai.neargo.shop.food.service.StoreOperationService;
import ai.neargo.shop.food.spi.ServiceCalendarInternalApi;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Internal calendar adapter protected by the original service-token security chain. */
@RestController
@Validated
@ConditionalOnFoodEnabled
@Conditional(PersistenceEnabled.class)
public class InternalServiceCalendarController {
    private final StoreOperationService service;
    public InternalServiceCalendarController(StoreOperationService service) { this.service = service; }

    /** Platform-authenticated actor and original key are forwarded without creating another user session. */
    @PostMapping(ServiceCalendarInternalApi.SAVE_PATH)
    public OperationVO saveCalendar(@Valid @RequestBody ServiceCalendarInternalApi.Command command) {
        return service.saveCalendar(command.entityNo(), command.storeNo(), command.localDate(), command.actorNo(),
                command.calendar(), command.idempotencyKey());
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
