package ai.neargo.shop.invbridge;

import ai.neargo.shop.spi.product.ExecutionStockMirrorEvidencePort;
import ai.neargo.shop.spi.product.ExecutionStockRetentionPort.RetentionLookup;
import ai.neargo.shop.spi.trade.ExecutionOriginalEvidence;

/** HOST historical producer and frozen component proof lookup. */
public interface ExecutionStockMirrorEvidenceService extends ExecutionStockMirrorEvidencePort {
    MirrorOriginalProof freeze(RetentionLookup lookup, ExecutionOriginalEvidence core);
    void validateFrozen(MirrorOriginalProof proof);
}
