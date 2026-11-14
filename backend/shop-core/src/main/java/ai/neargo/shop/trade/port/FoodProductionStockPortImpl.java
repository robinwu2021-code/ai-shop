package ai.neargo.shop.trade.port;

import ai.neargo.shop.spi.food.FoodProductionStockPort;
import ai.neargo.shop.spi.inventory.InventoryProductionPort;
import ai.neargo.shop.trade.stock.FoodProductionStockService;
import org.springframework.stereotype.Component;

/** Industry entry point to the original order's production stock authority. */
@Component
public final class FoodProductionStockPortImpl implements FoodProductionStockPort {
    private final FoodProductionStockService service;

    public FoodProductionStockPortImpl(FoodProductionStockService service) {
        this.service = service;
    }

    @Override public String owner(InventoryProductionPort.Command command) {
        return service.owner(command);
    }

    @Override public InventoryProductionPort.Receipt consume(InventoryProductionPort.Command command) {
        return service.consume(command);
    }

    @Override public InventoryProductionPort.Receipt query(InventoryProductionPort.Command command) {
        return service.query(command);
    }

    @Override public InventoryProductionPort.Receipt reverse(InventoryProductionPort.Reverse command) {
        return service.reverse(command);
    }
}
