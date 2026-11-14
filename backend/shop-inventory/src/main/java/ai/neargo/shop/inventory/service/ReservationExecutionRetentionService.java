package ai.neargo.shop.inventory.service;

import ai.neargo.shop.spi.product.InventoryExecutionStockRetentionPort.AcceptedRetention;
import ai.neargo.shop.spi.product.InventoryExecutionStockRetentionPort.ComponentIssued;
import ai.neargo.shop.spi.product.InventoryExecutionStockRetentionPort.ComponentLookup;
import ai.neargo.shop.spi.product.InventoryExecutionStockRetentionPort.ComponentReceipt;
import ai.neargo.shop.spi.product.InventoryExecutionStockRetentionPort.ComponentRelease;
import ai.neargo.shop.spi.product.InventoryExecutionStockRetentionPort.ComponentRetain;

/** Native owner service stays separate from HOST aggregation and exports no second aggregate/component bean. */
public interface ReservationExecutionRetentionService {
    ComponentReceipt retainComponent(ComponentRetain request);
    ComponentReceipt queryOriginalComponent(ComponentLookup request);
    ComponentReceipt releaseAfterNoEffect(ComponentRelease request);
    ComponentReceipt acknowledgeIssued(ComponentIssued request);
    ComponentReceipt continueAcceptedRetention(AcceptedRetention request);
}
