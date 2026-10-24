package ai.neargo.shop.spi.inventory;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/** Internal transport preserves the full source command for exact original-event replay. */
public interface InventoryProductionInternalApi {
    String CONSUME = "/internal/inventory/production/consume";
    String QUERY = "/internal/inventory/production/query";
    @PostExchange(CONSUME)
    InventoryProductionPort.Receipt consume(@RequestBody InventoryProductionPort.Command command);
    @PostExchange(QUERY)
    InventoryProductionPort.Receipt query(@RequestBody InventoryProductionPort.Command command);
}
