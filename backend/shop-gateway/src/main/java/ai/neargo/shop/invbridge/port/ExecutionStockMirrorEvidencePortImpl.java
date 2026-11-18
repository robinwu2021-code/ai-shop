package ai.neargo.shop.invbridge.port;

import ai.neargo.shop.invbridge.ExecutionStockMirrorEvidenceService;
import ai.neargo.shop.spi.product.ExecutionStockMirrorEvidencePort;
import ai.neargo.shop.spi.product.InventoryExecutionStockRetentionPort.ComponentLookup;
import org.springframework.stereotype.Component;

/** Exports frozen HOST mirror evidence without exposing its producer operations. */
@Component
public final class ExecutionStockMirrorEvidencePortImpl implements ExecutionStockMirrorEvidencePort {
    private final ExecutionStockMirrorEvidenceService service;

    public ExecutionStockMirrorEvidencePortImpl(ExecutionStockMirrorEvidenceService service) {
        this.service = service;
    }

    @Override public MirrorOriginalProof queryOriginal(ComponentLookup request) {
        return service.queryOriginal(request);
    }
}
