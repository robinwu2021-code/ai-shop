package ai.neargo.shop.invbridge;

import ai.neargo.shop.spi.product.ExecutionStockMirrorEvidencePort.MirrorOriginalProof;
import ai.neargo.shop.spi.product.InventoryExecutionStockRetentionPort.ComponentLookup;
import ai.neargo.shop.spi.product.ExecutionStockRetentionPort.RetentionLookup;
import ai.neargo.shop.spi.trade.ExecutionOriginalEvidence;

/** HOST historical producer and frozen component proof lookup. */
public interface ExecutionStockMirrorEvidenceService {
    MirrorOriginalProof queryOriginal(ComponentLookup request);
    MirrorOriginalProof freeze(RetentionLookup lookup, ExecutionOriginalEvidence core);
    void validateFrozen(MirrorOriginalProof proof);
}
