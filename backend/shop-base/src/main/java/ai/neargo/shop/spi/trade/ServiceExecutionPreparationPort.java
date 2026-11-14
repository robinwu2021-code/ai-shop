package ai.neargo.shop.spi.trade;

import ai.neargo.shop.spi.product.ExecutionStockRetentionPort.RetainExecutionStock;
import ai.neargo.shop.spi.product.ExecutionStockRetentionPort.RetentionLookup;
import ai.neargo.shop.spi.product.ExecutionStockRetentionPreparationObservation;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionLookup;

/**
 * Deployment bridge for the four consumed preparation operations; aggregate authority remains with
 * HOST.
 */
public interface ServiceExecutionPreparationPort {
    ExecutionReference referenceOriginal(ExecutionLookup request);

    ExecutionOriginalEvidence queryOriginalEvidence(ExecutionEvidenceLookup request);

    ExecutionStockRetentionPreparationObservation retainStock(RetainExecutionStock request);

    ExecutionStockRetentionPreparationObservation queryStockOriginal(RetentionLookup request);
}
