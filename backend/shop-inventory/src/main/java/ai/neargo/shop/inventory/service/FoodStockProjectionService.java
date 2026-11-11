package ai.neargo.shop.inventory.service;

import ai.neargo.inventory.api.projection.StockProjectionContracts;
import ai.neargo.shop.spi.inventory.InventoryFoodStockProjectionPort.Mapping;
import ai.neargo.shop.spi.inventory.InventoryFoodStockProjectionPort.MappingResult;
import ai.neargo.shop.spi.inventory.InventoryFoodStockProjectionPort.Receipt;
import ai.neargo.shop.spi.inventory.InventoryFoodStockProjectionPort.Scope;
import ai.neargo.shop.spi.inventory.InventoryFoodStockProjectionPort.Snapshot;
import java.util.List;

/** Remote stock mirror service, including permission-resolved local query views. */
public interface FoodStockProjectionService {
    List<MappingResult> validateMappings(Scope scope, List<Mapping> mappings);
    Receipt activate(Scope scope, long generation, String operationKey);
    Receipt deactivate(Scope scope, long generation, String operationKey);
    Receipt apply(Scope scope, long generation, String eventNo, String payloadHash, Snapshot snapshot);
    Receipt queryReceipt(Scope scope, String eventNo, String payloadHash);
    List<Snapshot> queryProjection(Scope scope, List<String> itemNos);
    StockProjectionContracts.ProjectionPage list(Scope scope, int page, int size);
    StockProjectionContracts.ProjectionView get(Scope scope, String item);
}
