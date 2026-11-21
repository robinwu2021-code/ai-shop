package ai.neargo.shop.invbridge;

import ai.neargo.shop.event.DomainEvent;
import ai.neargo.shop.spi.product.StockPort.SkuQty;
import java.util.List;

/** Source-database outbox command used when order/payment facts need a cross-database inventory transition. */
public record InventoryLifecycleCommand(String type, String ref, List<SkuQty> items, String orderNo)
        implements DomainEvent {
    @Override public String aggregateType() { return "INVENTORY"; }
    @Override public String aggregateId() { return ref; }
    @Override public String eventType() { return type; }
}
