package ai.neargo.shop.trade.port;

import ai.neargo.shop.spi.trade.ServiceExecutionPort;
import ai.neargo.shop.trade.service.ServiceExecutionService;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/** Routes every quantity operation to the real Core owner, independently of beauty deployment shape. */
@Component
@Primary
public final class ServiceExecutionPortImpl implements ServiceExecutionPort {
    private final ServiceExecutionService service;
    public ServiceExecutionPortImpl(ServiceExecutionService service) { this.service = service; }
    @Override
    public Grant prepare(PrepareExecution request) {
        return service.prepare(request);
    }
    @Override
    public Grant query(ExecutionLookup request) {
        return service.query(request);
    }
    @Override
    public Grant queryOriginal(ExecutionLookup request) {
        return service.queryOriginal(request);
    }
}
