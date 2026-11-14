package ai.neargo.shop.merchant.port;

import ai.neargo.shop.merchant.service.ResourceOccupationLifecycleService;
import ai.neargo.shop.spi.resource.ResourceOccupationLifecycleContract.Request;
import ai.neargo.shop.spi.resource.ResourceOccupationLifecycleContract.Result;
import ai.neargo.shop.spi.resource.ResourceOccupationLifecycleContract.CurrentOccupation;
import ai.neargo.shop.spi.resource.ResourceOccupationLifecycleContract.Cancellation;
import ai.neargo.shop.spi.resource.ResourceOccupationLifecycleContract.CurrentWorkflowProof;
import ai.neargo.shop.spi.resource.ResourceOccupationContract.OriginalLookup;
import ai.neargo.shop.spi.resource.ServiceTaskStartAuthorityPort.StartAuthorityLookup;
import ai.neargo.shop.spi.resource.ResourceOccupationLifecyclePort;
import java.util.Optional;
import org.springframework.validation.annotation.Validated;

@Validated
public class ResourceOccupationLifecyclePortImpl implements ResourceOccupationLifecyclePort {
    private final ResourceOccupationLifecycleService service;
    public ResourceOccupationLifecyclePortImpl(ResourceOccupationLifecycleService service) { this.service = service; }
    @Override public Cancellation cancelBeforeEffect(Request request) { return service.cancelBeforeEffect(request); }
    @Override public Optional<Cancellation> queryCancellationOriginal(Request request) { return service.queryCancellationOriginal(request); }
    @Override public CurrentWorkflowProof validateCurrentStart(StartAuthorityLookup authority,OriginalLookup original) { return service.validateCurrentStart(authority,original); }
    @Override public CurrentWorkflowProof validateCurrentWorkflow(Request request) { return service.validateCurrentWorkflow(request); }
    @Override public CurrentOccupation queryCurrent(OriginalLookup original) { return service.queryCurrent(original); }
    @Override public Result execute(Request request) { return service.execute(request); }
    @Override public Optional<Result> queryOriginal(Request request) { return service.queryOriginal(request); }
}
