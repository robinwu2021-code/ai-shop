package ai.neargo.shop.inventory.port;

import ai.neargo.shop.inventory.config.ConditionalOnInventory;
import ai.neargo.shop.inventory.service.ReservationExecutionRetentionService;
import ai.neargo.shop.spi.product.InventoryExecutionStockRetentionPort;
import org.springframework.stereotype.Component;

/** The sole Inventory component SPI bean delegates to its actual independent datasource owner. */
@Component
@ConditionalOnInventory
public final class InventoryExecutionStockRetentionPortImpl implements InventoryExecutionStockRetentionPort {
    private final ReservationExecutionRetentionService owner;

    public InventoryExecutionStockRetentionPortImpl(ReservationExecutionRetentionService owner) {
        this.owner = owner;
    }

    @Override
    public ComponentReceipt retainComponent(ComponentRetain request) {
        return owner.retainComponent(request);
    }

    @Override
    public ComponentReceipt queryOriginalComponent(ComponentLookup request) {
        return owner.queryOriginalComponent(request);
    }

    @Override
    public ComponentReceipt releaseAfterNoEffect(ComponentRelease request) {
        return owner.releaseAfterNoEffect(request);
    }

    @Override
    public ComponentReceipt acknowledgeIssued(ComponentIssued request) {
        return owner.acknowledgeIssued(request);
    }

    @Override
    public ComponentReceipt continueAcceptedRetention(AcceptedRetention request) {
        return owner.continueAcceptedRetention(request);
    }
}
