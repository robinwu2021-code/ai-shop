package ai.neargo.shop.spi.resource;

import java.util.Optional;

/** Trusted industry-server contract. Source services must verify ownership before calling this owner bridge. */
public interface ResourceBookingPort {
    /** Stable key and exact payload must already be committed in source storage before this call. */
    Receipt accept(Actor actor, Accept request);
    Optional<Receipt> query(Actor actor, Scope scope, String commandNo);
    Receipt execute(Actor actor, Scope scope, String commandNo);
    Hold queryHold(Actor actor, ResourceSourceAuthorityPort.Binding binding, String holdNo);
    /** Read-only restart recovery of an already bound intent; never accepts, executes or issues authority. */
    Optional<Receipt> reconcile(ResourceSourceAuthorityPort.Binding binding);
    /** Terminal reconciliation may inspect a hold without recreating the initiating user's authority. */
    Hold reconcileHold(ResourceSourceAuthorityPort.Binding binding, String holdNo);

    record Scope(String tenantNo, String entityNo, String storeNo) { }
    record Actor(String operatorNo, String realm, String subjectKind) { }
    record Target(String holdNo, long holdVersion, long leaseRevision) { }
    record Accept(Scope scope, String action, String key, String sourceType, String sourceNo,
            long expectedOwnerVersion, long sourceRevision, Target target, String frozenPayload) { }
    record Receipt(ResourceSourceAuthorityPort.Binding binding, String ownerNo, String status, long revision,
            long expectedOwnerVersion, Target target, String frozenPayload, String resultPayload,
            String rejectionCode) { }
    record Hold(String holdNo, String status, long version, long leaseRevision, long sourceRevision,
            long ownerVersion, String planHash) { }
}
