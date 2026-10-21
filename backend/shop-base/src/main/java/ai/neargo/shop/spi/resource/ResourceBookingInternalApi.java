package ai.neargo.shop.spi.resource;

import ai.neargo.shop.spi.security.DelegatedIdentity;
import java.util.List;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/** Neutral machine-only booking owner protocol; all source identities and payload bytes remain intact. */
public interface ResourceBookingInternalApi {
    String BASE = "/internal/resource-booking/";
    @PostExchange(BASE + "accept") ResourceBookingPort.Receipt accept(@RequestBody Acceptance request);
    @PostExchange(BASE + "query") Lookup query(@RequestBody Command request);
    @PostExchange(BASE + "execute") ResourceBookingPort.Receipt execute(@RequestBody Command request);
    @PostExchange(BASE + "hold") BoundHold hold(@RequestBody HoldQuery request);
    @PostExchange(BASE + "reconcile") Lookup reconcile(@RequestBody ResourceSourceAuthorityPort.Binding binding);
    @PostExchange(BASE + "reconcile-hold") BoundHold reconcileHold(@RequestBody BoundHoldQuery request);
    @PostExchange(BASE + "candidates") List<ResourceFactsPort.ResourceFact> candidates(
            @RequestBody ResourceCandidatesPort.Query query);
    @PostExchange(BASE + "capacity") List<ResourceCandidatesPort.Capacity> capacity(
            @RequestBody ResourceCandidatesPort.CapacityQuery query);
    record Acceptance(DelegatedIdentity identity, ResourceBookingPort.Actor actor, ResourceBookingPort.Accept
            request) { }
    record Command(DelegatedIdentity identity, ResourceBookingPort.Actor actor, ResourceBookingPort.Scope scope,
            String commandNo) { }
    record HoldQuery(DelegatedIdentity identity, ResourceBookingPort.Actor actor,
            ResourceSourceAuthorityPort.Binding binding, String holdNo) { }
    record BoundHoldQuery(ResourceSourceAuthorityPort.Binding binding, String holdNo) { }
    record BoundHold(ResourceSourceAuthorityPort.Binding binding, ResourceBookingPort.Hold hold) { }
    /** Explicit found is required so HTTP200 error envelopes never decode into an absent command. */
    record Lookup(Boolean found, ResourceBookingPort.Receipt receipt) {
        public Lookup {
            if (found == null || found != (receipt != null)) {
                throw new IllegalArgumentException("explicit lookup result required");
            }
        }
    }
}
