package ai.neargo.shop.merchant.port;

import ai.neargo.shop.merchant.service.ResourceOccupationService;
import ai.neargo.shop.spi.resource.ResourceOccupationContract.CancelOriginal;
import ai.neargo.shop.spi.resource.ResourceOccupationContract.CancellationResult;
import ai.neargo.shop.spi.resource.ResourceOccupationContract.StartRequest;
import ai.neargo.shop.spi.resource.ResourceOccupationContract.StartResult;
import ai.neargo.shop.spi.resource.ResourceOccupationPort;
import org.springframework.validation.annotation.Validated;

@Validated
public class ResourceOccupationPortImpl implements ResourceOccupationPort {
    private final ResourceOccupationService service;
    public ResourceOccupationPortImpl(ResourceOccupationService service) { this.service = service; }
    @Override public StartResult start(StartRequest request) { return service.start(request); }
    @Override public CancellationResult cancelBeforeEffect(CancelOriginal request) { return service.cancelBeforeEffect(request); }
}
