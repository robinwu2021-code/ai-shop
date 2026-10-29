package ai.neargo.shop.commerce.port;

import ai.neargo.shop.spi.commerce.ChannelAvailabilitySourcePort;
import ai.neargo.shop.spi.inventory.InventoryAvailabilityPort;

public final class InventoryChannelAvailabilitySource implements ChannelAvailabilitySourcePort {
    private final InventoryAvailabilityPort inventory;

    public InventoryChannelAvailabilitySource(InventoryAvailabilityPort inventory) {
        this.inventory = inventory;
    }

    @Override
    public Kind kind() {
        return Kind.STOCK;
    }

    @Override
    public Facts capture(Query query) {
        if (query.kind() != Kind.STOCK)
            throw new UnsupportedOperationException("AVAILABILITY_OWNER_UNSUPPORTED");
        var facts =
                inventory.capture(
                        new InventoryAvailabilityPort.Query(
                                query.entityNo(), query.storeNo(), query.resourceRef()));
        if (facts == null
                || facts.status() != InventoryAvailabilityPort.Status.KNOWN
                || facts.data() == null)
            throw new UnsupportedOperationException("ORIGINAL_INVENTORY_AVAILABILITY_UNKNOWN");
        var data = facts.data();
        if (!data.query()
                .equals(
                        new InventoryAvailabilityPort.Query(
                                query.entityNo(), query.storeNo(), query.resourceRef())))
            throw new IllegalArgumentException("INVENTORY_AVAILABILITY_SCOPE_MISMATCH");
        return new Facts(
                new Data(
                        query,
                        State.QUANTITY,
                        data.availableQuantity(),
                        data.unit(),
                        data.revision()),
                facts.observedAt(),
                facts.validUntil(),
                facts.receiptRef());
    }
}
