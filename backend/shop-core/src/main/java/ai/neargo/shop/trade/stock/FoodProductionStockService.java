package ai.neargo.shop.trade.stock;

import ai.neargo.shop.spi.inventory.InventoryProductionPort;

/** Resolves and executes the original order's authoritative production stock owner. */
public interface FoodProductionStockService {
    String owner(InventoryProductionPort.Command command);
    InventoryProductionPort.Receipt consume(InventoryProductionPort.Command command);
    InventoryProductionPort.Receipt query(InventoryProductionPort.Command command);
    InventoryProductionPort.Receipt reverse(InventoryProductionPort.Reverse command);
}
