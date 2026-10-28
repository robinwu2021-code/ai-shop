package ai.neargo.shop.trade.port;

import ai.neargo.shop.spi.trade.ExecutionEvidenceLookup;
import ai.neargo.shop.spi.trade.ExecutionOriginalEvidence;
import ai.neargo.shop.spi.trade.ServiceExecutionEvidencePort;
import ai.neargo.shop.trade.service.ServiceExecutionService;
import org.springframework.stereotype.Component;

/** Thin local bridge to real Core facts; no manual payload parsing or saved actor restoration. */
@Component
public final class ServiceExecutionEvidencePortImpl implements ServiceExecutionEvidencePort {
    private final ServiceExecutionService service;
    public ServiceExecutionEvidencePortImpl(ServiceExecutionService service) {
        this.service = service;
    }

    @Override
    public ExecutionOriginalEvidence queryOriginalEvidence(ExecutionEvidenceLookup request) {
        return service.queryOriginalEvidence(request);
    }

    @Override
    public CurrentActor authorizeCurrent(ExecutionEvidenceLookup request) {
        return service.authorizeCurrent(request);
    }
}
