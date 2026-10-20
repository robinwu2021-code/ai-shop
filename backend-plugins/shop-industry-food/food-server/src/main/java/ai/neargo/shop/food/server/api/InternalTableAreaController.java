package ai.neargo.shop.food.server.api;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.config.ConditionalOnFoodEnabled;
import ai.neargo.shop.food.config.FoodPersistenceConfiguration.PersistenceEnabled;
import ai.neargo.shop.food.dto.TableAreaResultVO;
import ai.neargo.shop.food.dto.TableAreaVO;
import ai.neargo.shop.food.service.TableService;
import ai.neargo.shop.food.spi.TableInternalApi;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Internal table-area adapters use the original service token and one authoritative TableService. */
@RestController
@Validated
@ConditionalOnFoodEnabled
@Conditional(PersistenceEnabled.class)
public class InternalTableAreaController {
    private final TableService service;
    public InternalTableAreaController(TableService service) { this.service = service; }

    @PostMapping(TableInternalApi.LIST_PATH)
    public PageData<TableAreaVO> listTableAreas(@Valid @RequestBody TableInternalApi.Lookup c) {
        return service.listTableAreas(c.entityNo(), c.storeNo(), c.query());
    }
    @PostMapping(TableInternalApi.CREATE_PATH)
    public TableAreaResultVO createTableArea(@Valid @RequestBody TableInternalApi.CreateCommand c) {
        return service.createTableArea(c.entityNo(), c.storeNo(), c.actorNo(), c.area(), c.idempotencyKey());
    }
    @PostMapping(TableInternalApi.SAVE_PATH)
    public TableAreaResultVO saveTableArea(@Valid @RequestBody TableInternalApi.SaveCommand c) {
        return service.saveTableArea(c.entityNo(), c.storeNo(), c.areaNo(), c.actorNo(), c.area(), c.idempotencyKey());
    }
    @PostMapping(TableInternalApi.STATUS_PATH)
    public TableAreaResultVO setStatusTableArea(@Valid @RequestBody TableInternalApi.StatusCommand c) {
        return service.setStatusTableArea(c.entityNo(), c.storeNo(), c.areaNo(), c.actorNo(),
                c.area(), c.idempotencyKey());
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
