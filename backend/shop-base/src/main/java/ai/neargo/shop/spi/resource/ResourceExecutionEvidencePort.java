package ai.neargo.shop.spi.resource;

import ai.neargo.shop.spi.resource.ResourceOccupationContract.OriginalLookup;
import ai.neargo.shop.spi.resource.ResourceOccupationContract.OriginalResult;
import jakarta.validation.Valid;

/** Exact immutable Native originals for Core ACK, recovery and retention release; no saved HUMAN authority. */
public interface ResourceExecutionEvidencePort {
    OriginalResult queryOriginal(@Valid OriginalLookup original);
}
