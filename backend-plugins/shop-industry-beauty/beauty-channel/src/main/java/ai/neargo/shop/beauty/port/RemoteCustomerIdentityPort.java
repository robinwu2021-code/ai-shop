package ai.neargo.shop.beauty.port;

import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.member.CustomerIdentityInternalApi;
import ai.neargo.shop.spi.member.CustomerIdentityPort;
import ai.neargo.svc.client.ServiceCalls;

/** Reads the actual member owner; missing capability never silently degrades to a guest customer. */
public final class RemoteCustomerIdentityPort implements CustomerIdentityPort {
    private final CustomerIdentityInternalApi api;
    public RemoteCustomerIdentityPort(CustomerIdentityInternalApi api) { this.api = api; }
    @Override public ResolvedCustomer verifyConsumerProof(EntitlementContext context, String memberCode) {
        var identity = DelegatedIdentityContext.current(context.actorNo());
        var request = new CustomerIdentityInternalApi.ProofRequest(identity, context, memberCode);
        var result = ServiceCalls.call("PLATFORM", () -> api.verifyConsumerProof(request));
        if (result == null || !request.equals(result.request()) || result.customer() == null
                || !context.actorNo().equals(result.customer().userNo())) {
            throw new IllegalStateException("invalid consumer proof response");
        }
        return result.customer();
    }
    @Override public ResolvedCustomer resolveReference(EntitlementContext context, ReferenceQuery query) {
        var identity = DelegatedIdentityContext.current(context.actorNo());
        var result = ServiceCalls.call("PLATFORM", () -> api.resolve(
                new CustomerIdentityInternalApi.Request(identity, context, query)));
        if (result == null || !identity.equals(result.identity()) || !context.equals(result.context())
                || !query.equals(result.query()) || result.customer() == null || result.customer().userNo() == null
                || result.customer().userNo().isBlank()) { throw new
                        IllegalStateException("invalid customer response"); }
        return result.customer();
    }
}
