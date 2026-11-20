package ai.neargo.shop.invbridge.port;

import ai.neargo.shop.invbridge.ExecutionStockRetentionCoordinator;
import ai.neargo.shop.spi.product.ExecutionStockRetentionIntentPort;
import org.springframework.stereotype.Component;

/** Exports persisted accepted component authority from the HOST continuation owner. */
@Component
public final class ExecutionStockRetentionIntentPortImpl implements ExecutionStockRetentionIntentPort {
    private final ExecutionStockRetentionCoordinator coordinator;

    public ExecutionStockRetentionIntentPortImpl(ExecutionStockRetentionCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    @Override public AcceptedComponentAuthority queryAcceptedComponent(AcceptedComponentLookup request) {
        return coordinator.queryAcceptedComponent(request);
    }
}
