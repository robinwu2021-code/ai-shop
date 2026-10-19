package ai.neargo.shop.pay.entitlement.port;

import ai.neargo.shop.pay.entitlement.service.EntitlementGrantService;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementGrantPort;

/** Local transport delegates to the same source validation and pay transactions as remote requests. */
public class EntitlementGrantPortImpl implements EntitlementGrantPort {
    private final EntitlementGrantService service;
    public EntitlementGrantPortImpl(EntitlementGrantService service) { this.service = service; }
    @Override public GrantView prepare(EntitlementContext context, GrantCommand command) {
        return service.prepare(context, command);
    }
    @Override public GrantView commit(EntitlementContext context, GrantDecision command) {
        return service.commit(context, command);
    }
    @Override public GrantView abort(EntitlementContext context, AbortGrant command) {
        return service.abort(context, command);
    }
    @Override public GrantView query(EntitlementContext context, String entity, String no) {
        return service.query(context, entity, no);
    }
}
