package ai.neargo.shop.beauty.port;

import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.member.CustomerIdentityInternalApi;
import ai.neargo.shop.spi.member.CustomerIdentityPort;
import ai.neargo.svc.client.ServiceCalls;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.security.InternalCall;
import ai.neargo.shop.spi.security.InternalRequestBinding;
import ai.neargo.shop.svc.InternalBindings;

/** Reads the actual member owner; missing capability never silently degrades to a guest customer. */
public final class RemoteCustomerIdentityPort implements CustomerIdentityPort {
    private final CustomerIdentityInternalApi api;
    private final InternalBindings bindings;

    public RemoteCustomerIdentityPort(CustomerIdentityInternalApi api) {
        this(api, null);
    }

    /**
     * Counter selectors need complete binding while older ticket protocols retain their
     * constructor.
     */
    public RemoteCustomerIdentityPort(CustomerIdentityInternalApi api, InternalBindings bindings) {
        this.api = api;
        this.bindings = bindings;
    }

    @Override
    public ResolvedCustomer resolveCounterReference(
            EntitlementContext context, CounterReferenceQuery query) {
        var identity = DelegatedIdentityContext.current(context.actorNo());
        if (!"MERCHANT".equals(identity.realm())
                || !context.tenantNo().equals(identity.tenantNo())) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        var payload =
                new CustomerIdentityInternalApi.CounterReferenceRequest(identity, context, query);
        var call =
                new InternalCall<>(
                        new InternalRequestBinding(
                                1,
                                CustomerIdentityInternalApi.COUNTER_ENDPOINT,
                                "RESOLVE",
                                identity,
                                query.entityNo(),
                                query.storeNo(),
                                query.memberNo()),
                        payload);
        try {
            var reply = ServiceCalls.call("PLATFORM", () -> api.resolveCounterReference(call));
            bindings.require(call, reply);
            var result = reply.result();
            if (!payload.equals(result.request())
                    || result.customer() == null
                    || result.customer().userNo() == null
                    || result.customer().userNo().isBlank()
                    || result.customer().userNo().length() > 64
                    || !result.customer().userNo().equals(result.customer().userNo().strip())
                    || !identity.equals(DelegatedIdentityContext.current(context.actorNo()))) {
                throw new IllegalArgumentException("invalid counter reference response");
            }
            return result.customer();
        } catch (RuntimeException unavailable) {
            throw BizException.of(ErrorCode.BEAUTY_DEPENDENCY_UNAVAILABLE);
        }
    }

    @Override public ResolvedCustomer resolveClaimReference(EntitlementContext context, ReferenceQuery query) {
        var identity = DelegatedIdentityContext.current(context.actorNo());
        var request = new CustomerIdentityInternalApi.ClaimReferenceRequest(identity, context, query);
        var result = ServiceCalls.call("PLATFORM", () -> api.resolveClaimReference(request));
        if (result == null || !request.equals(result.request()) || result.customer() == null
                || result.customer().userNo() == null || result.customer().userNo().isBlank()) {
            throw new IllegalStateException("invalid strict claim reference response");
        }
        return result.customer();
    }
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
