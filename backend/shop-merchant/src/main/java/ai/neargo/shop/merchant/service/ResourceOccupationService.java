package ai.neargo.shop.merchant.service;

import ai.neargo.shop.spi.resource.ResourceOccupationContract.CancelOriginal;
import ai.neargo.shop.spi.resource.ResourceOccupationContract.CancellationResult;
import ai.neargo.shop.spi.resource.ResourceOccupationContract.StartRequest;
import ai.neargo.shop.spi.resource.ResourceOccupationContract.StartResult;

/** Actual HUMAN start and persisted-cause MACHINE cancellation orchestration. */
public interface ResourceOccupationService {
    StartResult start(StartRequest request);
    CancellationResult cancelBeforeEffect(CancelOriginal request);
}
