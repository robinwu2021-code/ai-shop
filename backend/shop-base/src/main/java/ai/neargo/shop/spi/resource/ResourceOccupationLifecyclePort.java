package ai.neargo.shop.spi.resource;

import ai.neargo.shop.spi.resource.ResourceOccupationLifecycleContract.Request;
import ai.neargo.shop.spi.resource.ResourceOccupationLifecycleContract.Result;
import ai.neargo.shop.spi.resource.ResourceOccupationLifecycleContract.CurrentOccupation;
import ai.neargo.shop.spi.resource.ResourceOccupationLifecycleContract.Cancellation;
import ai.neargo.shop.spi.resource.ResourceOccupationLifecycleContract.CurrentWorkflowProof;
import ai.neargo.shop.spi.resource.ResourceOccupationContract.OriginalLookup;
import ai.neargo.shop.spi.resource.ServiceTaskStartAuthorityPort.StartAuthorityLookup;
import jakarta.validation.Valid;
import java.util.Optional;

/** Current HUMAN effects and exact historical result reads have separate authorization semantics. */
public interface ResourceOccupationLifecyclePort {
    Cancellation cancelBeforeEffect(@Valid Request request);
    Optional<Cancellation> queryCancellationOriginal(@Valid Request request);
    CurrentWorkflowProof validateCurrentStart(@Valid StartAuthorityLookup authority,@Valid OriginalLookup original);
    CurrentWorkflowProof validateCurrentWorkflow(@Valid Request request);
    CurrentOccupation queryCurrent(@Valid OriginalLookup original);
    Result execute(@Valid Request request);
    Optional<Result> queryOriginal(@Valid Request request);
}
