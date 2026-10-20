package ai.neargo.shop.food.service;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.dto.CreateTableAreaReq;
import ai.neargo.shop.food.dto.ListTableAreasQuery;
import ai.neargo.shop.food.dto.SaveTableAreaReq;
import ai.neargo.shop.food.dto.SetStatusTableAreaReq;
import ai.neargo.shop.food.dto.TableAreaResultVO;
import ai.neargo.shop.food.dto.TableAreaVO;

/** Table-area configuration shares one local/remote contract without owning payment or inventory. */
public interface TableService {
    PageData<TableAreaVO> listTableAreas(String entityNo, String storeNo, ListTableAreasQuery query);
    TableAreaResultVO createTableArea(String entityNo, String storeNo, String actorNo,
            CreateTableAreaReq req, String idempotencyKey);
    TableAreaResultVO saveTableArea(String entityNo, String currentStoreNo, String areaNo, String actorNo,
            SaveTableAreaReq req, String idempotencyKey);
    TableAreaResultVO setStatusTableArea(String entityNo, String currentStoreNo, String areaNo, String actorNo,
            SetStatusTableAreaReq req, String idempotencyKey);
}
