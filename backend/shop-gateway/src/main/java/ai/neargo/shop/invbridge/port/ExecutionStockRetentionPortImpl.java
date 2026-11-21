package ai.neargo.shop.invbridge.port;

import ai.neargo.shop.product.service.ExecutionStockRetentionService;
import ai.neargo.shop.spi.product.ExecutionStockRetentionPort;
import ai.neargo.shop.spi.product.ExecutionStockRetentionAggregateContract.*;
import ai.neargo.shop.invbridge.ExecutionStockRetentionCoordinator;
import org.springframework.stereotype.Component;

/** Single exported aggregate provider. B1 component validates frozen PLATFORM authority before any mutation. */
@Component
public final class ExecutionStockRetentionPortImpl implements ExecutionStockRetentionPort {
    private final ExecutionStockRetentionService platform;
    private final ExecutionStockRetentionCoordinator coordinator;
    public ExecutionStockRetentionPortImpl(ExecutionStockRetentionService platform,
            ExecutionStockRetentionCoordinator coordinator) {
        this.platform = platform;
        this.coordinator = coordinator;
    }

    @Override
    public Receipt retain(RetainExecutionStock request) {
        return platform.retain(request);
    }

    @Override
    public Receipt queryOriginal(RetentionLookup request) {
        return platform.queryOriginal(request);
    }

    @Override
    public Receipt releaseAfterNoEffect(ReleaseAfterNoEffect request) {
        return platform.releaseAfterNoEffect(request);
    }

    @Override
    public Receipt acknowledgeIssued(AcknowledgeIssued request) {
        return platform.acknowledgeIssued(request);
    }
    @Override
    public AggregateReceiptV2 retainV2(RetainExecutionStock request) {
        return coordinator.retainV2(request);
    }

    @Override
    public AggregateReceiptV2 queryOriginalV2(RetentionLookup request) {
        return coordinator.queryOriginalV2(request);
    }

    @Override
    public AggregateReceiptV2 releaseAfterNoEffectV2(ReleaseAfterNoEffect request) {
        return coordinator.releaseAfterNoEffectV2(request);
    }

    @Override
    public AggregateReceiptV2 acknowledgeIssuedV2(AggregateIssuedV2 request) {
        return coordinator.acknowledgeIssuedV2(request);
    }

    @Override
    public AggregateReceiptV2 continueAcceptedRetentionV2(AcceptedRouteLookup request) {
        return coordinator.continueAcceptedRetentionV2(request);
    }

}
