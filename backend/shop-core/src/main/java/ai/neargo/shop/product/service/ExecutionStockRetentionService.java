package ai.neargo.shop.product.service;

import ai.neargo.shop.spi.product.ExecutionStockRetentionPort.AcknowledgeIssued;
import ai.neargo.shop.spi.product.ExecutionStockRetentionPort.Receipt;
import ai.neargo.shop.spi.product.ExecutionStockRetentionPort.ReleaseAfterNoEffect;
import ai.neargo.shop.spi.product.ExecutionStockRetentionPort.RetainExecutionStock;
import ai.neargo.shop.spi.product.ExecutionStockRetentionPort.RetentionLookup;

/**
 * PLATFORM component; HOST alone exports the aggregate port so future owner composition cannot be mistaken for success.
 */
public interface ExecutionStockRetentionService {
    Receipt retain(RetainExecutionStock request);
    Receipt queryOriginal(RetentionLookup request);
    Receipt releaseAfterNoEffect(ReleaseAfterNoEffect request);
    Receipt acknowledgeIssued(AcknowledgeIssued request);
}
