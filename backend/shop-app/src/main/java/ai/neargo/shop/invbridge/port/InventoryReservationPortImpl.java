package ai.neargo.shop.invbridge.port;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.inventory.service.InventoryAclService;
import ai.neargo.shop.inventory.service.ReservationService;
import ai.neargo.shop.spi.inventory.InventoryReservationInternalApi;
import ai.neargo.shop.spi.inventory.InventoryReservationPort;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;

/** Uses the existing inventory ledger and ACL; callers authorize their public actor/batch/store before this port. */
@Transactional(transactionManager = "invTransactionManager")
public class InventoryReservationPortImpl implements InventoryReservationPort {
    private final InventoryAclService acl;
    private final ReservationService reservations;

    public InventoryReservationPortImpl(InventoryAclService acl, ReservationService reservations) {
        this.acl = acl;
        this.reservations = reservations;
    }

    @Override
    public Snapshot reserve(Scope scope, List<Line> lines, long ttlSeconds, int lifecycleVersion) {
        var request = new InventoryReservationInternalApi.Reserve(scope, lines, ttlSeconds, lifecycleVersion);
        String owner = owner(scope);
        List<ReservationService.Line> mapped = request.lines().stream().map(line -> {
            if (!owner.equals(acl.ownerOfSku(line.skuNo()))) throw BizException.of(ErrorCode.FORBIDDEN);
            String item = acl.itemIdOfSku(line.skuNo());
            // Read-only source lookup rejects an unprojected/wrong-owner store instead of creating a default.
            String location = acl.stockLocationOfExistingStore(scope.entityNo(), line.storeNo());
            if (item == null || location == null) throw BizException.of(ErrorCode.NOT_FOUND);
            return new ReservationService.Line(item, location, line.qty(), line.fulfillmentRef());
        }).toList();
        reservations.reserve(owner, scope.externalRef(), mapped, ttlSeconds, lifecycleVersion);
        return snapshot(scope, owner);
    }

    @Override public Snapshot acceptUnpaid(Scope scope) {
        String owner = owner(scope);
        reservations.acceptUnpaid(owner, scope.externalRef());
        return snapshot(scope, owner);
    }
    @Override public Snapshot allocatePaid(Scope scope) {
        String owner = owner(scope);
        reservations.allocatePaid(owner, scope.externalRef());
        return snapshot(scope, owner);
    }
    @Override public Snapshot issue(Scope scope, String fulfillmentRef) {
        new InventoryReservationInternalApi.Issue(scope, fulfillmentRef);
        String owner = owner(scope);
        String outbound = reservations.issue(owner, scope.externalRef(), fulfillmentRef, "INTERNAL_HANDOFF");
        if (outbound == null) throw BizException.of(ErrorCode.CONFLICT);
        return snapshot(scope, owner);
    }
    @Override public Snapshot cancelAll(Scope scope) {
        String owner = owner(scope);
        reservations.cancelByRef(owner, scope.externalRef());
        return snapshot(scope, owner);
    }
    @Override @Transactional(transactionManager = "invTransactionManager", readOnly = true)
    public Snapshot query(Scope scope) { return snapshot(scope, owner(scope)); }

    private String owner(Scope scope) {
        if (scope == null) throw BizException.of(ErrorCode.BAD_REQUEST);
        if (!"MAIN".equals(scope.tenantNo())) throw BizException.of(ErrorCode.FORBIDDEN);
        String owner = acl.findOwnerId(scope.entityNo());
        if (owner == null) throw BizException.of(ErrorCode.NOT_FOUND);
        return owner;
    }

    private Snapshot snapshot(Scope scope, String owner) {
        var found = reservations.query(owner, scope.externalRef());
        if (found.isEmpty()) return new Snapshot(scope, null, State.ABSENT, 0, false, List.of());
        var actual = found.orElseThrow();
        List<ReservedLine> original = actual.lines().stream().map(line -> {
            String sku = acl.skuOfItem(owner, line.itemId());
            if (sku == null) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
            return new ReservedLine(sku, line.qty(), line.fulfillmentRef());
        }).toList();
        return new Snapshot(scope, actual.reservationId(), State.valueOf(actual.status().name()),
                actual.lifecycleVersion(), actual.paymentAcknowledged(), original);
    }
}
