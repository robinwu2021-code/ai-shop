package ai.neargo.shop.beauty.port;

import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.beauty.spi.BeautySourceInternalApi;
import ai.neargo.shop.spi.resource.ResourceSourceAuthorityPort;
import ai.neargo.svc.client.ServiceCalls;

/** Only the channel-only HOST registers this reverse source callback; SERVER retains its local source owner. */
public final class RemoteAppointmentSourceAuthorityPort implements ResourceSourceAuthorityPort {
    private final BeautySourceInternalApi api;
    public RemoteAppointmentSourceAuthorityPort(BeautySourceInternalApi api) { this.api = api; }
    @Override public String sourceType() { return "BEAUTY_APPOINTMENT"; }
    @Override public Decision freeze(Request request) { return call(request, true); }
    @Override public Decision query(Request request) { return call(request, false); }
    private Decision call(Request request, boolean freeze) {
        var identity = DelegatedIdentityContext.current(request.operatorNo());
        if (!sourceType().equals(request.binding().sourceType()) || !identity.realm().equals(request.realm())
                || !identity.subjectKind().equals(request.subjectKind())
                || !identity.tenantNo().equals(request.binding().tenantNo())) {
            throw new IllegalArgumentException("current bound source actor required");
        }
        var body = new BeautySourceInternalApi.Delegation(identity, request);
        var result = ServiceCalls.call("BEAUTY", () -> freeze ? api.freeze(body) : api.query(body));
        if (result == null || result.state() == null || !request.binding().equals(result.binding())) {
            throw new IllegalStateException("invalid source decision identity");
        }
        return result;
    }
}
