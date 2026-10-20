package ai.neargo.shop.food.service;

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

/** Table and area configuration share one local/remote contract without owning payment or inventory. */
public interface TableService {
    PageData<TableAreaVO> listTableAreas(String entityNo, String storeNo, ListTableAreasQuery query);
    TableAreaResultVO createTableArea(String entityNo, String storeNo, String actorNo,
            CreateTableAreaReq req, String idempotencyKey);
    TableAreaResultVO saveTableArea(String entityNo, String currentStoreNo, String areaNo, String actorNo,
            SaveTableAreaReq req, String idempotencyKey);
    TableAreaResultVO setStatusTableArea(String entityNo, String currentStoreNo, String areaNo, String actorNo,
            SetStatusTableAreaReq req, String idempotencyKey);
    PageData<TableVO> listTables(String entityNo, String storeNo, ListTablesQuery query);
    TableResultVO getTable(String entityNo, String currentStoreNo, String tableNo);
    TableResultVO createTable(String entityNo, String storeNo, String actorNo, CreateTableReq req,
            String idempotencyKey);
    TableResultVO saveTable(String entityNo, String currentStoreNo, String tableNo, String actorNo, SaveTableReq req,
            String idempotencyKey);
    TableResultVO setStatusTable(String entityNo, String currentStoreNo, String tableNo, String actorNo,
            SetStatusTableReq req, String idempotencyKey);
    OperationResultVO archiveTable(String entityNo, String currentStoreNo, String tableNo, String actorNo,
            ArchiveTableReq req, String idempotencyKey);
}
