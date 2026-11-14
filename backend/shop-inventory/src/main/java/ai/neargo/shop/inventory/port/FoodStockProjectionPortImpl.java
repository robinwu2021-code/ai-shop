package ai.neargo.shop.inventory.port;

import ai.neargo.shop.inventory.config.ConditionalOnInventory;
import ai.neargo.shop.inventory.service.FoodStockProjectionService;
import ai.neargo.shop.spi.inventory.InventoryFoodStockProjectionPort;
import java.util.List;
import org.springframework.stereotype.Component;

/** Exports mirror synchronization separately from the local projection query API. */
@Component
@ConditionalOnInventory
public final class FoodStockProjectionPortImpl implements InventoryFoodStockProjectionPort {
    private final FoodStockProjectionService service;

    public FoodStockProjectionPortImpl(FoodStockProjectionService service) {
        this.service = service;
    }

    @Override public List<MappingResult> validateMappings(Scope scope, List<Mapping> mappings) {
        return service.validateMappings(scope, mappings);
    }

    @Override public Receipt activate(Scope scope, long generation, String operationKey) {
        return service.activate(scope, generation, operationKey);
    }

    @Override public Receipt deactivate(Scope scope, long generation, String operationKey) {
        return service.deactivate(scope, generation, operationKey);
    }

    @Override public Receipt apply(Scope scope, long generation, String eventNo, String payloadHash, Snapshot snapshot) {
        return service.apply(scope, generation, eventNo, payloadHash, snapshot);
    }

    @Override public Receipt queryReceipt(Scope scope, String eventNo, String payloadHash) {
        return service.queryReceipt(scope, eventNo, payloadHash);
    }

    @Override public List<Snapshot> queryProjection(Scope scope, List<String> itemNos) {
        return service.queryProjection(scope, itemNos);
    }
}
