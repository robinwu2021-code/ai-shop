package ai.neargo.shop.spi.food;
import ai.neargo.shop.spi.inventory.InventoryProductionPort;
/** Resolves original order stock ownership before a kitchen event reaches a stock executor. */
public interface FoodProductionStockPort {
    String owner(InventoryProductionPort.Command command);
    InventoryProductionPort.Receipt consume(InventoryProductionPort.Command command);
    InventoryProductionPort.Receipt query(InventoryProductionPort.Command command);
    InventoryProductionPort.Receipt reverse(InventoryProductionPort.Reverse command);
}
