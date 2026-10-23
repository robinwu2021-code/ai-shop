package ai.neargo.shop.food.spi;

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
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/** Typed restaurant transport carries trusted platform context without creating a second business API. */
public interface TableInternalApi {
    String LIST_PATH = "/internal/food/table-areas/list";
    String CREATE_PATH = "/internal/food/table-areas/create";
    String SAVE_PATH = "/internal/food/table-areas/save";
    String STATUS_PATH = "/internal/food/table-areas/set-status";

    @PostExchange(LIST_PATH)
    PageData<TableAreaVO> listTableAreas(@RequestBody Lookup request);
    @PostExchange(CREATE_PATH)
    TableAreaResultVO createTableArea(@RequestBody CreateCommand request);
    @PostExchange(SAVE_PATH)
    TableAreaResultVO saveTableArea(@RequestBody SaveCommand request);
    @PostExchange(STATUS_PATH)
    TableAreaResultVO setStatusTableArea(@RequestBody StatusCommand request);

    record Lookup(@NotBlank @Size(max=32) String entityNo, @NotBlank @Size(max=32) String storeNo,
            @Valid ListTableAreasQuery query) { }
    record CreateCommand(@NotBlank @Size(max=32) String entityNo, @NotBlank @Size(max=32) String storeNo,
            @NotBlank @Size(max=32) String actorNo, @NotBlank @Size(max=128) String idempotencyKey,
            @Valid @NotNull CreateTableAreaReq area) { }
    record SaveCommand(@NotBlank @Size(max=32) String entityNo, @NotBlank @Size(max=32) String storeNo,
            @NotBlank @Size(max=32) String areaNo, @NotBlank @Size(max=32) String actorNo,
            @NotBlank @Size(max=128) String idempotencyKey, @Valid @NotNull SaveTableAreaReq area) { }
    record StatusCommand(@NotBlank @Size(max=32) String entityNo, @NotBlank @Size(max=32) String storeNo,
            @NotBlank @Size(max=32) String areaNo, @NotBlank @Size(max=32) String actorNo,
            @NotBlank @Size(max=128) String idempotencyKey, @Valid @NotNull SetStatusTableAreaReq area) { }
    String TABLE_LIST_PATH = "/internal/food/tables/list";
    @PostExchange(TABLE_LIST_PATH)
    PageData<TableVO> listTables(@RequestBody TableLookup request);
    String TABLE_GET_PATH = "/internal/food/tables/get";
    @PostExchange(TABLE_GET_PATH)
    TableResultVO getTable(@RequestBody TableIdentity request);
    String TABLE_CREATE_PATH = "/internal/food/tables/create";
    @PostExchange(TABLE_CREATE_PATH)
    TableResultVO createTable(@RequestBody TableCreateCommand request);
    String TABLE_SAVE_PATH = "/internal/food/tables/save";
    @PostExchange(TABLE_SAVE_PATH)
    TableResultVO saveTable(@RequestBody TableSaveCommand request);
    String TABLE_STATUS_PATH = "/internal/food/tables/set-status";
    @PostExchange(TABLE_STATUS_PATH)
    TableResultVO setStatusTable(@RequestBody TableStatusCommand request);
    String TABLE_ARCHIVE_PATH = "/internal/food/tables/archive";
    @PostExchange(TABLE_ARCHIVE_PATH)
    OperationResultVO archiveTable(@RequestBody TableArchiveCommand request);
    record TableLookup(@NotBlank @Size(max=32) String entityNo, @NotBlank @Size(max=32) String storeNo,
            @Valid ListTablesQuery query) { }
    record TableIdentity(@NotBlank @Size(max=32) String entityNo, @NotBlank @Size(max=32) String storeNo,
            @NotBlank @Size(max=32) String tableNo) { }
    record TableCreateCommand(@NotBlank @Size(max=32) String entityNo, @NotBlank @Size(max=32) String storeNo,
            @NotBlank @Size(max=32) String actorNo,
            @NotBlank @Size(max=128) String idempotencyKey,
            @Valid @NotNull CreateTableReq table) { }
    record TableSaveCommand(@NotBlank @Size(max=32) String entityNo, @NotBlank @Size(max=32) String storeNo,
            @NotBlank @Size(max=32) String tableNo, @NotBlank @Size(max=32) String actorNo,
            @NotBlank @Size(max=128) String idempotencyKey,
            @Valid @NotNull SaveTableReq table) { }
    record TableStatusCommand(@NotBlank @Size(max=32) String entityNo, @NotBlank @Size(max=32) String storeNo,
            @NotBlank @Size(max=32) String tableNo, @NotBlank @Size(max=32) String actorNo,
            @NotBlank @Size(max=128) String idempotencyKey,
            @Valid @NotNull SetStatusTableReq table) { }
    record TableArchiveCommand(@NotBlank @Size(max=32) String entityNo, @NotBlank @Size(max=32) String storeNo,
            @NotBlank @Size(max=32) String tableNo, @NotBlank @Size(max=32) String actorNo,
            @NotBlank @Size(max=128) String idempotencyKey,
            @Valid @NotNull ArchiveTableReq table) { }
}
