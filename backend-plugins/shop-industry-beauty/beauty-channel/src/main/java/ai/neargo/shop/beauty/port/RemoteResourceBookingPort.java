package ai.neargo.shop.beauty.port;

import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.spi.resource.ResourceBookingInternalApi;
import ai.neargo.shop.spi.resource.ResourceBookingPort;
import ai.neargo.shop.spi.resource.ResourcePlanContract;
import ai.neargo.shop.spi.resource.ResourceSourceAuthorityPort.Binding;
import ai.neargo.svc.client.ServiceCalls;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.Optional;
import java.util.Set;

/** Exact receipt/binding validation keeps malformed success envelopes from becoming owner evidence. */
public final class RemoteResourceBookingPort implements ResourceBookingPort {
    private final ResourceBookingInternalApi api;
    public RemoteResourceBookingPort(ResourceBookingInternalApi api) { this.api = api; }
    @Override public Receipt accept(Actor actor, Accept request) {
        var result = call(() -> api.accept(new ResourceBookingInternalApi.Acceptance(
                DelegatedIdentityContext.current(actor.operatorNo()), actor, request)));
        receipt(result);
        var b = result.binding();
        require(scope(b).equals(request.scope()) && b.sourceType().equals(request.sourceType())
                && b.sourceNo().equals(request.sourceNo()) && b.sourceRevision() == request.sourceRevision()
                && b.action().equals(request.action()) && result.expectedOwnerVersion() ==
                        request.expectedOwnerVersion()
                && Objects.equals(result.target(), request.target())
                && result.frozenPayload().equals(request.frozenPayload()));
        return result;
    }
    @Override public Optional<Receipt> query(Actor actor, Scope scope, String commandNo) {
        return command(actor, scope, commandNo, false);
    }
    @Override public Receipt execute(Actor actor, Scope scope, String commandNo) {
        return command(actor, scope, commandNo, true).orElseThrow();
    }
    private Optional<Receipt> command(Actor actor, Scope scope, String number, boolean execute) {
        var request = new ResourceBookingInternalApi.Command(DelegatedIdentityContext.current(actor.operatorNo()),
                actor, scope, number);
        var lookup = execute ? new ResourceBookingInternalApi.Lookup(true, call(() -> api.execute(request)))
                : call(() -> api.query(request));
        require(lookup != null && lookup.found() != null);
        if (!lookup.found()) { return Optional.empty(); }
        var result = lookup.receipt(); receipt(result);
        require(scope(result.binding()).equals(scope) && result.binding().commandNo().equals(number));
        return Optional.of(result);
    }
    @Override public Hold queryHold(Actor actor, Binding binding, String holdNo) {
        return hold(call(() -> api.hold(new ResourceBookingInternalApi.HoldQuery(
                DelegatedIdentityContext.current(actor.operatorNo()), actor, binding, holdNo))), binding, holdNo);
    }
    @Override public Optional<Receipt> reconcile(Binding binding) {
        var result = call(() -> api.reconcile(binding));
        require(result != null && result.found() != null);
        if (!result.found()) { return Optional.empty(); }
        receipt(result.receipt()); require(binding.equals(result.receipt().binding()));
        return Optional.of(result.receipt());
    }
    @Override public Hold reconcileHold(Binding binding, String holdNo) {
        return hold(call(() -> api.reconcileHold(new ResourceBookingInternalApi.BoundHoldQuery(binding, holdNo))),
                binding, holdNo);
    }
    private Hold hold(ResourceBookingInternalApi.BoundHold response, Binding binding, String holdNo) {
        require(response != null && binding.equals(response.binding()));
        var h = response.hold();
        require(h != null && holdNo.equals(h.holdNo()) && h.status() != null && h.version() >= 0
                && h.leaseRevision() >= 0 && h.sourceRevision() >= 0 && h.ownerVersion() >= 0
                && h.planHash() != null && h.planHash().matches("[0-9a-f]{64}"));
        return h;
    }
    private void receipt(Receipt r) {
        require(r != null && r.binding() != null && r.ownerNo() != null && !r.ownerNo().isBlank()
                && Set.of("ACCEPTED", "PENDING", "SUCCEEDED", "REJECTED", "UNKNOWN").contains(r.status())
                && r.revision() >= 0 && r.expectedOwnerVersion() >= 0 && r.frozenPayload() != null
                && ResourcePlanContract.hash(r.frozenPayload()).equals(r.binding().planHash()));
    }
    private Scope scope(Binding b) { return new Scope(b.tenantNo(), b.entityNo(), b.storeNo()); }
    private <T> T call(Supplier<T> operation) {
        DelegatedIdentityContext.outside(); return ServiceCalls.call("PLATFORM", operation);
    }
    private void require(boolean valid) { if (!valid) { throw new IllegalStateException("invalid owner response"); } }
}
