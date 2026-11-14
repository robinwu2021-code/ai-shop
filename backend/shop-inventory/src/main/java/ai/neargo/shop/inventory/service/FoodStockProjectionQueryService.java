package ai.neargo.shop.inventory.service;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.spi.inventory.InventoryFoodStockProjectionPort.Snapshot;
import java.time.LocalDateTime;

/** Permission-resolved source scope is provided by the B adapter. */
public interface FoodStockProjectionQueryService {
    public record ProjectionVO(String sourceSystem,String sourceEntityNo,String sourceStoreNo,
            long generation,String syncStatus,LocalDateTime receivedAt,Snapshot stock){}
    PageData<ProjectionVO> list(String entity, String store, int page, int size);
    ProjectionVO get(String entity, String store, String item);
}
