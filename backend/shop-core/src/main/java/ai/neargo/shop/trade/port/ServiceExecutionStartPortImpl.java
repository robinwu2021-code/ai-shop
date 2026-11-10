package ai.neargo.shop.trade.port;

import ai.neargo.shop.spi.trade.ExecutionEvidenceLookup;
import ai.neargo.shop.spi.trade.ExecutionStartContract.AcknowledgeResourceStart;
import ai.neargo.shop.spi.trade.ExecutionStartContract.StartAcknowledgement;
import ai.neargo.shop.spi.trade.ServiceExecutionStartPort;
import ai.neargo.shop.trade.service.ServiceExecutionService;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/** Thin machine bridge to the existing Core execution root owner. */
@Component
@Validated
public class ServiceExecutionStartPortImpl implements ServiceExecutionStartPort {
    private final ServiceExecutionService service;

    public ServiceExecutionStartPortImpl(ServiceExecutionService service) {
        this.service = service;
    }

    @Override
    public StartAcknowledgement markStarted(AcknowledgeResourceStart request) {
        return service.markStarted(request);
    }

    @Override
    public StartAcknowledgement queryStartOriginal(ExecutionEvidenceLookup request) {
        return service.queryStartOriginal(request);
    }
}
