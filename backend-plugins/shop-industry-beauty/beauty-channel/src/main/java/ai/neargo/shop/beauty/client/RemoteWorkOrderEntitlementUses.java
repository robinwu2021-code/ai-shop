package ai.neargo.shop.beauty.client;

import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.ServiceWorkOrderUseInternalApi;
import ai.neargo.shop.spi.entitlement.ServiceWorkOrderUsePort;
import ai.neargo.shop.svc.InternalBindings;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCalls;

public final class RemoteWorkOrderEntitlementUses implements ServiceWorkOrderUsePort {
    private final ServiceWorkOrderUseInternalApi api;
    private final InternalBindings bindings;
    public RemoteWorkOrderEntitlementUses(ServiceWorkOrderUseInternalApi api, InternalBindings bindings) {
        this.api = api; this.bindings = bindings;
    }
    @Override public Binding reserve(EntitlementContext context, Lookup lookup) {
        return BeautyReceptionCalls.call(ServiceWorkOrderUseInternalApi.ENDPOINT, "RESERVE", lookup.entityNo(),
                lookup.storeNo(), context.actorNo(), lookup.operationNo(), lookup, bindings, api::reserve);
    }
    @Override public Binding inspect(Lookup lookup) {
        return original(lookup, ServiceCalls.call(ServiceName.BEAUTY, () -> api.inspect(lookup)));
    }
    private Binding original(Lookup lookup, Binding binding) {
        if (binding == null || !lookup.equals(binding.lookup())) {
            throw ai.neargo.shop.common.BizException.of(ai.neargo.shop.common.ErrorCode.BEAUTY_DEPENDENCY_UNAVAILABLE);
        }
        return binding;
    }
    @Override public Binding reconcile(Lookup lookup) {
        return original(lookup, ServiceCalls.call(ServiceName.BEAUTY, () -> api.reconcile(lookup)));
    }
}
