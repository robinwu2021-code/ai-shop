package ai.neargo.shop.invbridge;

import ai.neargo.shop.spi.product.ExecutionStockRetentionAggregateContract.*;
import ai.neargo.shop.spi.product.ExecutionStockRetentionPort.*;
import ai.neargo.shop.spi.product.ExecutionStockRetentionIntentPort;

/** HOST aggregate orchestration and sole bounded continuation owner; no scheduled job. */
public interface ExecutionStockRetentionCoordinator extends ExecutionStockRetentionIntentPort {
    AggregateReceiptV2 retainV2(RetainExecutionStock request);
    AggregateReceiptV2 queryOriginalV2(RetentionLookup request);
    AggregateReceiptV2 continueAcceptedRetentionV2(AcceptedRouteLookup request);
    AggregateReceiptV2 releaseAfterNoEffectV2(ReleaseAfterNoEffect request);
    AggregateReceiptV2 acknowledgeIssuedV2(AggregateIssuedV2 request);
    ReconcileResult reconcileAcceptedRoutes(ReconcileCursor cursor, int limit);
}
