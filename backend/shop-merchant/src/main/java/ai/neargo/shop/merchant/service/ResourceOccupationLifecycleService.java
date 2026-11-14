package ai.neargo.shop.merchant.service;

import ai.neargo.shop.spi.resource.ResourceOccupationLifecycleContract.Request;
import ai.neargo.shop.spi.resource.ResourceOccupationLifecycleContract.Result;
import ai.neargo.shop.spi.resource.ResourceOccupationLifecycleContract.CurrentOccupation;
import ai.neargo.shop.spi.resource.ResourceOccupationLifecycleContract.Cancellation;
import ai.neargo.shop.spi.resource.ResourceOccupationLifecycleContract.CurrentWorkflowProof;
import ai.neargo.shop.spi.resource.ResourceOccupationContract.OriginalLookup;
import ai.neargo.shop.spi.resource.ServiceTaskStartAuthorityPort.StartAuthorityLookup;
import java.util.Optional;

public interface ResourceOccupationLifecycleService {
    Cancellation cancelBeforeEffect(Request request);
    Optional<Cancellation> queryCancellationOriginal(Request request);
    CurrentWorkflowProof validateCurrentStart(StartAuthorityLookup authority,OriginalLookup original);
    CurrentWorkflowProof validateCurrentWorkflow(Request request);
    CurrentOccupation queryCurrent(OriginalLookup original);
    Result execute(Request request);
    Optional<Result> queryOriginal(Request request);
}
