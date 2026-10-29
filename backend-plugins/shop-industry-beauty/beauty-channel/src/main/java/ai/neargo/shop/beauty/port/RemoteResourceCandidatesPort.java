package ai.neargo.shop.beauty.port;

import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.spi.resource.ResourceBookingInternalApi;
import ai.neargo.shop.spi.resource.ResourceCandidatesPort;
import ai.neargo.shop.spi.resource.ResourceFactsPort;
import ai.neargo.svc.client.ServiceCalls;
import java.util.List;

/** Capacity transports independent factAt and complete replacement evidence without defaults. */
public final class RemoteResourceCandidatesPort implements ResourceCandidatesPort {
    private final ResourceBookingInternalApi api;
    public RemoteResourceCandidatesPort(ResourceBookingInternalApi api) { this.api = api; }
    @Override public List<ResourceFactsPort.ResourceFact> candidates(Query query) {
        DelegatedIdentityContext.outside();
        var result = ServiceCalls.call("PLATFORM", () -> api.candidates(query));
        if (result == null || result.size() > query.limit() || result.stream().anyMatch(r -> r == null
                || r.resourceNo() == null || r.resourceType() == null || r.capacity() < 1
                || r.storeNo() != null && !query.scope().storeNo().equals(r.storeNo()))) {
            throw new IllegalStateException("invalid candidate response");
        }
        return List.copyOf(result);
    }
    @Override public List<Capacity> capacity(CapacityQuery query) {
        DelegatedIdentityContext.outside();
        var result = ServiceCalls.call("PLATFORM", () -> api.capacity(query));
        if (result == null || result.stream().anyMatch(c -> c == null || !query.resourceNos().contains(c.resourceNo())
                || c.from() == null || c.to() == null || !c.to().isAfter(c.from())
                || !c.from().isBefore(query.to()) || !c.to().isAfter(query.from())
                || c.capacity() < 0 || c.used() < 0)) { throw new IllegalStateException("invalid capacity response"); }
        return List.copyOf(result);
    }
}
