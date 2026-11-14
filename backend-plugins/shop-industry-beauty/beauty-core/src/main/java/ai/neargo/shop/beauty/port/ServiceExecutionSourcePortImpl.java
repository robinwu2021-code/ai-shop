package ai.neargo.shop.beauty.port;

import ai.neargo.shop.beauty.service.ServiceTaskExecutionService;
import ai.neargo.shop.spi.trade.ServiceExecutionSourcePort;

/** Exactly one local source owner delegates to the same committed application original. */
public final class ServiceExecutionSourcePortImpl implements ServiceExecutionSourcePort {
    private final ServiceTaskExecutionService service;
    public ServiceExecutionSourcePortImpl(ServiceTaskExecutionService service) { this.service = service; }
    @Override public StartIntent freeze(FreezeStart request) { return service.freeze(request); }
    @Override public StartIntent inspect(StartIntentLookup request) { return service.inspect(request); }
    @Override public StartIntent inspectOriginal(StartIntentLookup request) { return service.inspectOriginal(request); }
}
