package ai.neargo.shop.spi.resource;

import ai.neargo.shop.spi.resource.ResourceOccupationContract.CancelOriginal;
import ai.neargo.shop.spi.resource.ResourceOccupationContract.CancellationResult;
import ai.neargo.shop.spi.resource.ResourceOccupationContract.StartRequest;
import ai.neargo.shop.spi.resource.ResourceOccupationContract.StartResult;
import jakarta.validation.Valid;

/** HUMAN start and MACHINE cancellation; original queries use the separate read-only evidence port. */
public interface ResourceOccupationPort {
    StartResult start(@Valid StartRequest request);
    CancellationResult cancelBeforeEffect(@Valid CancelOriginal original);
}
