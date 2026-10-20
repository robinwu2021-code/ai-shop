package ai.neargo.shop.food.server.api;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.config.ConditionalOnFoodEnabled;
import ai.neargo.shop.food.config.FoodPersistenceConfiguration.PersistenceEnabled;
import ai.neargo.shop.food.dto.OperationResultVO;
import ai.neargo.shop.food.dto.TableResultVO;
import ai.neargo.shop.food.dto.TableVO;
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

/** Internal table transport reuses the platform service token without introducing a user session. */
@RestController
@Validated
@ConditionalOnFoodEnabled
@Conditional(PersistenceEnabled.class)
public class InternalTableController {
    private final TableService service;
    public InternalTableController(TableService service) { this.service=service; }
    @PostMapping(TableInternalApi.TABLE_LIST_PATH)
    public PageData<TableVO> listTables(@Valid @RequestBody TableInternalApi.TableLookup c) {
        return service.listTables(c.entityNo(), c.storeNo(), c.query());
    }
    @PostMapping(TableInternalApi.TABLE_GET_PATH)
    public TableResultVO getTable(@Valid @RequestBody TableInternalApi.TableIdentity c) {
        return service.getTable(c.entityNo(), c.storeNo(), c.tableNo());
    }
    @PostMapping(TableInternalApi.TABLE_CREATE_PATH)
    public TableResultVO createTable(@Valid @RequestBody TableInternalApi.TableCreateCommand c) {
        return service.createTable(c.entityNo(), c.storeNo(), c.actorNo(), c.table(), c.idempotencyKey());
    }
    @PostMapping(TableInternalApi.TABLE_SAVE_PATH)
    public TableResultVO saveTable(@Valid @RequestBody TableInternalApi.TableSaveCommand c) {
        return service.saveTable(c.entityNo(), c.storeNo(), c.tableNo(), c.actorNo(),
                c.table(), c.idempotencyKey());
    }
    @PostMapping(TableInternalApi.TABLE_STATUS_PATH)
    public TableResultVO setStatusTable(@Valid @RequestBody TableInternalApi.TableStatusCommand c) {
        return service.setStatusTable(c.entityNo(), c.storeNo(), c.tableNo(), c.actorNo(),
                c.table(), c.idempotencyKey());
    }
    @PostMapping(TableInternalApi.TABLE_ARCHIVE_PATH)
    public OperationResultVO archiveTable(@Valid @RequestBody TableInternalApi.TableArchiveCommand c) {
        return service.archiveTable(c.entityNo(), c.storeNo(), c.tableNo(), c.actorNo(),
                c.table(), c.idempotencyKey());
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
