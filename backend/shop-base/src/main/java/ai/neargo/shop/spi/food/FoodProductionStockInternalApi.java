package ai.neargo.shop.spi.food;
import ai.neargo.shop.spi.inventory.InventoryProductionPort;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;
public interface FoodProductionStockInternalApi {
    String BASE="/internal/food-stock/production/";
    @PostExchange(BASE+"owner") String owner(@RequestBody InventoryProductionPort.Command command);
    @PostExchange(BASE+"consume") InventoryProductionPort.Receipt consume(@RequestBody InventoryProductionPort.Command command);
    @PostExchange(BASE+"reverse") InventoryProductionPort.Receipt reverse(@RequestBody InventoryProductionPort.Reverse command);
    @PostExchange(BASE+"query") InventoryProductionPort.Receipt query(@RequestBody InventoryProductionPort.Command command);
}
