package ai.neargo.shop.food.foodclient;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.dto.ArchiveTableReq;
import ai.neargo.shop.food.dto.CreateTableAreaReq;
import ai.neargo.shop.food.dto.CreateTableReq;
import ai.neargo.shop.food.dto.ListTableAreasQuery;
import ai.neargo.shop.food.dto.ListTablesQuery;
import ai.neargo.shop.food.dto.OperationResultVO;
import ai.neargo.shop.food.dto.SaveTableAreaReq;
import ai.neargo.shop.food.dto.SaveTableReq;
import ai.neargo.shop.food.dto.SetStatusTableAreaReq;
import ai.neargo.shop.food.dto.SetStatusTableReq;
import ai.neargo.shop.food.dto.TableAreaResultVO;
import ai.neargo.shop.food.dto.TableAreaVO;
import ai.neargo.shop.food.dto.TableResultVO;
import ai.neargo.shop.food.dto.TableVO;
import ai.neargo.shop.food.service.TableService;
import ai.neargo.shop.food.spi.TableInternalApi;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;
import java.util.function.Supplier;

/** Stateless transport preserves the local service contract and never retries configuration writes. */
public class RemoteTableService implements TableService {
    private final TableInternalApi api;
    public RemoteTableService(TableInternalApi api) { this.api = api; }

    @Override
    public PageData<TableAreaVO> listTableAreas(String entity, String store, ListTableAreasQuery query) {
        return invoke(() -> api.listTableAreas(new TableInternalApi.Lookup(entity, store, query)));
    }
    @Override
    public TableAreaResultVO createTableArea(String entity, String store, String actor, CreateTableAreaReq req,
            String key) {
        return invoke(() -> api.createTableArea(new TableInternalApi.CreateCommand(entity, store, actor, key, req)));
    }
    @Override
    public TableAreaResultVO saveTableArea(String entity, String store, String area, String actor,
            SaveTableAreaReq req, String key) {
        return invoke(() -> api.saveTableArea(new TableInternalApi.SaveCommand(entity, store, area, actor, key, req)));
    }
    @Override
    public TableAreaResultVO setStatusTableArea(String entity, String store, String area, String actor,
            SetStatusTableAreaReq req, String key) {
        return invoke(() -> api.setStatusTableArea(
                new TableInternalApi.StatusCommand(entity, store, area, actor, key, req)));
    }

    private <T> T invoke(Supplier<T> call) {
        try {
            return ServiceCalls.call(ServiceName.FOOD, call);
        } catch (ServiceCallException failure) {
            ErrorCode business = switch (failure.statusCode()) {
                case 400 -> ErrorCode.BAD_REQUEST;
                case 403 -> ErrorCode.FORBIDDEN;
                case 404 -> ErrorCode.NOT_FOUND;
                case 409 -> ErrorCode.CONFLICT;
                default -> null;
            };
            if (business != null) throw BizException.of(business);
            throw failure;
        }
    }
    @Override
    public PageData<TableVO> listTables(String entityNo, String storeNo, ListTablesQuery query) {
        return invoke(() -> api.listTables(new TableInternalApi.TableLookup(entityNo, storeNo, query)));
    }
    @Override
    public TableResultVO getTable(String entityNo, String currentStoreNo, String tableNo) {
        return invoke(() -> api.getTable(new TableInternalApi.TableIdentity(entityNo, currentStoreNo, tableNo)));
    }
    @Override
    public TableResultVO createTable(String entityNo, String storeNo, String actorNo, CreateTableReq req, String key) {
        return invoke(() -> api.createTable(new TableInternalApi.TableCreateCommand(
                entityNo, storeNo, actorNo, key, req)));
    }
    @Override
    public TableResultVO saveTable(String entityNo, String currentStoreNo, String tableNo, String actorNo,
            SaveTableReq req, String key) {
        return invoke(() -> api.saveTable(new TableInternalApi.TableSaveCommand(
                entityNo, currentStoreNo, tableNo, actorNo, key, req)));
    }
    @Override
    public TableResultVO setStatusTable(String entityNo, String currentStoreNo, String tableNo, String actorNo,
            SetStatusTableReq req, String key) {
        return invoke(() -> api.setStatusTable(new TableInternalApi.TableStatusCommand(
                entityNo, currentStoreNo, tableNo, actorNo, key, req)));
    }
    @Override
    public OperationResultVO archiveTable(String entityNo, String currentStoreNo, String tableNo, String actorNo,
            ArchiveTableReq req, String key) {
        return invoke(() -> api.archiveTable(new TableInternalApi.TableArchiveCommand(
                entityNo, currentStoreNo, tableNo, actorNo, key, req)));
    }
}
