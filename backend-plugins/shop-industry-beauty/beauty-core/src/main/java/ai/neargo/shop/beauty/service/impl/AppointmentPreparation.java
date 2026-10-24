package ai.neargo.shop.beauty.service.impl;

import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.beauty.dto.AppointmentPreviewVO;
import ai.neargo.shop.beauty.entity.BtyActionLog;
import ai.neargo.shop.beauty.entity.BtyAppointment;
import ai.neargo.shop.beauty.entity.BtyAppointmentCurrent;
import ai.neargo.shop.beauty.entity.BtyAppointmentOperation;
import ai.neargo.shop.beauty.entity.BtyAppointmentPreparation;
import ai.neargo.shop.beauty.entity.BtyAppointmentResourceFence;
import ai.neargo.shop.beauty.entity.BtyReceptionOperation;
import ai.neargo.shop.beauty.mapper.BeautyMappers;
import ai.neargo.shop.beauty.policy.AppointmentAccess;
import ai.neargo.shop.beauty.policy.ReceptionIdentity;
import ai.neargo.shop.beauty.service.impl.ReceptionPreparation.WalkInPayload;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.idem.IdempotencyScope;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.member.CustomerIdentityPort;
import ai.neargo.shop.spi.product.ServiceCatalogFactsPort;
import ai.neargo.shop.spi.resource.ResourceBookingPort;
import ai.neargo.shop.spi.resource.ResourcePlanContract;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;

/** Commits stable requests before acceptance so unknown network responses can never generate replacement commands. */
public final class AppointmentPreparation {
    private final AppointmentPersistence db;
    private final AppointmentAccess access;
    private final ObjectProvider<CustomerIdentityPort> customers;
    private final ObjectProvider<ServiceCatalogFactsPort> products;
    private final ReceptionIdentity identities;
    private final BeautyMappers.ReceptionOperationMapper receptions;
    private final WorkOrderPreparation workOrders;
    private final Clock clock;
    public AppointmentPreparation(AppointmentPersistence db, AppointmentAccess access,
            ObjectProvider<CustomerIdentityPort> customers, ObjectProvider<ServiceCatalogFactsPort> products,
            ReceptionIdentity identities, BeautyMappers.ReceptionOperationMapper receptions,
            WorkOrderPreparation workOrders, Clock clock) {
        this.db = db;
        this.access = access;
        this.customers = customers;
        this.products = products;
        this.identities = identities; this.receptions = receptions; this.workOrders = workOrders; this.clock = clock;
    }
    public AppointmentPreviewVO save(AppointmentMatching.Matched match, String customerRef, BtyAppointment target,
            long expected) {
        return save(match, customerRef, null, target, expected);
    }
    public AppointmentPreviewVO save(AppointmentMatching.Matched match, String customerRef, String guestNo,
            BtyAppointment target, long expected) {
        var user = SecurityUtils.requireUser();
        String customer;
        String verifiedGuest = null;
        if (customerRef != null && guestNo != null) { throw BizException.of(ErrorCode.BAD_REQUEST); }
        ReceptionIdentity.VerifiedIdentity guest = guestNo == null ? null
                : identities.guest(match.scope().entityNo(), match.scope().storeNo(), guestNo);
        if (user.isConsumer()) {
            access.consumer(user.userNo()); customer = target == null ? user.userNo() : target.getCustomerRef();
        }
        else if (target != null) { customer = target.getCustomerRef(); }
        else if (guest != null) {
            customer = "USER".equals(guest.kind()) ? guest.reference() : null;
            verifiedGuest = "GUEST".equals(guest.kind()) ? guest.reference() : null;
        }
        else if (customerRef == null) { customer = null; }
        else {
            customer = customers.getObject().resolveReference(new EntitlementContext("MAIN", user.userNo(),
                    BizKey.next("BTCR"), IdempotencyScope.requestHash(customerRef)),
                    new CustomerIdentityPort.ReferenceQuery(match.scope().entityNo(), match.scope().storeNo(),
                            customerRef)).userNo();
            if (customer == null || customer.isBlank()) { throw BizException.of(ErrorCode.NOT_FOUND); }
        }
        var p = new BtyAppointmentPreparation();
        p.setEntityNo(match.scope().entityNo()); p.setStoreNo(match.scope().storeNo());
        p.setPreviewNo(BizKey.next("BTPR")); p.setActorNo(user.userNo());
        p.setActorRealm(user.realm().name()); p.setActorKind(user.subjectKind().name());
        p.setCustomerRef(customer);
        p.setGuestRef(customer == null ? verifiedGuest != null ? verifiedGuest
                : target == null ? BizKey.next("BTG") : target.getGuestRef() : null);
        p.setAppointmentNo(target == null ? null : target.getAppointmentNo()); p.setExpectedRevision(expected);
        p.setPolicyVersionNo(match.policyVersionNo()); p.setStartAt(match.startAt().toLocalDateTime());
        p.setEndAt(match.endAt().toLocalDateTime()); p.setTimezone("UTC");
        p.setFrozenPlan(match.plan()); p.setPlanHash(ResourcePlanContract.hash(match.plan()));
        p.setSnapshot(db.json.writeValueAsString(match.snapshot()));
        p.setExpiresAt(AppointmentPersistence.now().plusMinutes(5));
        db.tx(() -> { db.preparations.insert(p); return null; });
        return view(p);
    }
    public void customer(BtyAppointmentPreparation preview, String reference, String guestNo) {
        if (guestNo != null) {
            if (reference != null) { throw BizException.of(ErrorCode.BAD_REQUEST); }
            var identity = identities.guest(preview.getEntityNo(), preview.getStoreNo(), guestNo);
            AppointmentPersistence.require("USER".equals(identity.kind())
                    ? identity.reference().equals(preview.getCustomerRef()) && preview.getGuestRef() == null
                    : preview.getCustomerRef() == null && identity.reference().equals(preview.getGuestRef()));
            return;
        }
        if (preview.getGuestRef() != null && !preview.getGuestRef().startsWith("BTG")) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        if (reference == null) { AppointmentPersistence.require(preview.getCustomerRef() == null); return; }
        var resolved = customers.getObject().resolveReference(new EntitlementContext("MAIN",
                access.actor().operatorNo(),
                BizKey.next("BTCR"), IdempotencyScope.requestHash(reference)),
                new CustomerIdentityPort.ReferenceQuery(preview.getEntityNo(), preview.getStoreNo(), reference));
        AppointmentPersistence.require(resolved.userNo().equals(preview.getCustomerRef()));
    }
    public AppointmentPreviewVO view(BtyAppointmentPreparation p) {
        var snapshot = db.json.readValue(p.getSnapshot(), AppointmentMatching.Snapshot.class);
        return new AppointmentPreviewVO(p.getPreviewNo(), p.getExpiresAt().atOffset(ZoneOffset.UTC),
                p.getPolicyVersionNo(), p.getStartAt().atOffset(ZoneOffset.UTC), p.getEndAt().atOffset(ZoneOffset.UTC),
                snapshot.items(), snapshot.assignments(), snapshot.priceMinor());
    }
    public void own(BtyAppointmentPreparation p) {
        var actor = access.actor();
        if (!actor.operatorNo().equals(p.getActorNo()) || !actor.realm().equals(p.getActorRealm())
                || !actor.subjectKind().equals(p.getActorKind())) { throw BizException.of(ErrorCode.FORBIDDEN); }
        if ("CONSUMER".equals(actor.realm())) { access.consumer(actor.operatorNo()); }
        else { access.merchant(p.getEntityNo(), p.getStoreNo(), actor.operatorNo(), true); }
    }
    public BtyAppointmentOperation find(String entity, String store, String key, String hash) {
        var actor = access.actor();
        String digest = IdempotencyScope.requestHash(IdempotencyScope.requireKey(key));
        var op = db.read(() -> db.operations.selectOne(Wrappers.<BtyAppointmentOperation>lambdaQuery()
                .eq(BtyAppointmentOperation::getTenantNo, "MAIN").eq(BtyAppointmentOperation::getEntityNo, entity)
                .eq(BtyAppointmentOperation::getStoreNo, store).eq(BtyAppointmentOperation::getActorNo,
                        actor.operatorNo())
                .eq(BtyAppointmentOperation::getActorRealm, actor.realm())
                .eq(BtyAppointmentOperation::getActorKind, actor.subjectKind())
                .eq(BtyAppointmentOperation::getKeyHash, digest)));
        if (op != null) { AppointmentPersistence.require(hash.equals(op.getRequestHash())); }
        return op;
    }
    public BtyAppointmentOperation prepare(BtyAppointmentPreparation preview, BtyAppointment target,
            long expected, String action, String key, String hash, ResourceBookingPort.Hold hold,
            String requestEvidence) {
        return prepare(preview, target, expected, action, key, hash, hold, requestEvidence, () -> { }, null);
    }
    public BtyAppointmentOperation prepareReception(BtyAppointmentPreparation preview, BtyAppointment target,
            long expected, String key, String hash, PreparedWorkOrderScope scope, WorkOrderPreparation frozen,
            String requestEvidence) {
        return prepareReception(preview, target, expected, key, hash, scope, frozen, requestEvidence, null);
    }
    /** Link before accepting the resource command, within the existing root transaction. */
    public BtyAppointmentOperation prepareReception(BtyAppointmentPreparation preview, BtyAppointment target,
            long expected, String key, String hash, PreparedWorkOrderScope scope, WorkOrderPreparation frozen,
            String requestEvidence, String receptionOperationNo) {
        return prepare(preview, target, expected, "RECEPTION_CHANGE", key, hash, scope.frozen().holdEvidence().hold(),
                requestEvidence, () -> frozen.verifyLocked(scope), receptionOperationNo);
    }
    private BtyAppointmentOperation prepare(BtyAppointmentPreparation preview, BtyAppointment target,
            long expected, String action, String key, String hash, ResourceBookingPort.Hold hold,
            String requestEvidence, Runnable lockedCheck, String receptionOperationNo) {
        if ("CANCEL".equals(action) || "NO_SHOW".equals(action)) { access.root(target, true); }
        else { own(preview); }
        var existing = find(preview.getEntityNo(), preview.getStoreNo(), key, hash);
        if (existing != null) { return existing; }
        if ("CREATE".equals(action) || "CHANGE".equals(action)) {
            var frozen = db.json.readValue(preview.getSnapshot(), AppointmentMatching.Snapshot.class);
            for (var item : frozen.items()) {
                var observed = products.getObject().service(preview.getStoreNo(), item.skuNo());
                AppointmentPersistence.require(observed.goodsRevision() != null && observed.skuRevision() != null
                        && observed.goodsRevision().equals(item.goodsRevision())
                        && observed.skuRevision().equals(item.skuRevision())
                        && observed.priceMinor() == item.priceMinor() && observed.durationMin() == item.durationMin()
                        && preview.getEntityNo().equals(observed.store().entityNo())
                        && preview.getStoreNo().equals(observed.store().storeNo())
                        && item.skuNo().equals(observed.skuNo()) && item.goodsNo().equals(observed.goodsNo()));
            }
        }
        AppointmentPersistence.require(preview.getExpiresAt().isAfter(AppointmentPersistence.now())
                || "CANCEL".equals(action) || "NO_SHOW".equals(action));
        try {
            return db.tx(() -> {
                if (target == null) {
                    db.preparations.selectOne(Wrappers.<BtyAppointmentPreparation>lambdaQuery()
                            .eq(BtyAppointmentPreparation::getTenantNo, "MAIN")
                            .eq(BtyAppointmentPreparation::getEntityNo, preview.getEntityNo())
                            .eq(BtyAppointmentPreparation::getPreviewNo, preview.getPreviewNo()).last("FOR UPDATE"));
                    var replay = find(preview.getEntityNo(), preview.getStoreNo(), key, hash);
                    if (replay != null) { return replay; }
                    AppointmentPersistence.require(db.operations.selectCount(
                            Wrappers.<BtyAppointmentOperation>lambdaQuery()
                                    .eq(BtyAppointmentOperation::getTenantNo, "MAIN")
                                    .eq(BtyAppointmentOperation::getEntityNo, preview.getEntityNo())
                                    .eq(BtyAppointmentOperation::getPreviewNo, preview.getPreviewNo())
                                    .eq(BtyAppointmentOperation::getAction, "CREATE")) == 0);
                }
                var root = target == null ? createRoot(preview) : db.lock(target);
                AppointmentPersistence.require(root.getVersion() == expected || target == null && expected == 0);
                boolean reception = "RECEPTION_CHANGE".equals(action);
                AppointmentPersistence.require(reception ? "CHECKED_IN".equals(root.getStatus())
                        && db.roots.countWorkOrders(root.getEntityNo(), root.getStoreNo(), root.getAppointmentNo()) == 0
                        : "HELD".equals(root.getStatus()) || "CONFIRMED".equals(root.getStatus()));
                var fence = db.fences.selectScopedForUpdate(root.getEntityNo(), root.getStoreNo(),
                        root.getAppointmentNo());
                AppointmentPersistence.require(fence != null && fence.getActiveIntentNo() == null);
                if (target != null) {
                    AppointmentPersistence.require(Objects.equals(root.getHoldNo(), target.getHoldNo()));
                }
                var current = db.currents.selectScopedForUpdate(root.getEntityNo(), root.getStoreNo(),
                        root.getAppointmentNo());
                BtyReceptionOperation outer = null;
                if (receptionOperationNo != null) {
                    outer = receptions.selectScopedForUpdate(root.getEntityNo(), root.getStoreNo(),
                            receptionOperationNo);
                    AppointmentPersistence.require(outer != null && root.getAppointmentNo().equals(outer
                            .getAppointmentNo())
                            && "OPEN_WORK_ORDER".equals(outer.getAction())
                            && List.of("BAPI-052", "BAPI-054").contains(outer.getEndpoint())
                            && IdempotencyScope.requestHash("BEAUTY_OPEN_REPLACE_V1", outer.getDerivedKeyHash())
                                    .equals(key));
                    if (outer.getAppointmentOperationNo() != null) {
                        return db.operation(outer.getAppointmentOperationNo());
                    }
                    AppointmentPersistence.require("OPEN_PENDING".equals(outer.getPhase()));
                }
                lockedCheck.run();
                AppointmentPersistence.require(current.getOperationNo() == null);
                var op = prepareLocked(preview, root, action, key, hash, hold, requestEvidence,
                        BizKey.next("BTOP"));
                if (outer != null) {
                    outer.setAppointmentOperationNo(op.getOperationNo()); outer.setPhase("RESOURCE_PENDING");
                    AppointmentPersistence.require(receptions.updateById(outer) == 1);
                }
                return op;
            });
        } catch (DuplicateKeyException race) {
            var winner = find(preview.getEntityNo(), preview.getStoreNo(), key, hash);
            if (winner == null) { throw race; }
            return winner;
        }
    }
    private BtyAppointmentOperation prepareLocked(BtyAppointmentPreparation preview, BtyAppointment root,
            String action, String key, String hash, ResourceBookingPort.Hold hold, String requestEvidence,
            String operationNo) {
        var actor = access.actor();
        var op = new BtyAppointmentOperation();
        op.setEntityNo(root.getEntityNo()); op.setStoreNo(root.getStoreNo()); op.setOperationNo(operationNo);
        op.setActorNo(actor.operatorNo()); op.setActorRealm(actor.realm());
                op.setActorKind(actor.subjectKind());
        op.setKeyHash(IdempotencyScope.requestHash(IdempotencyScope.requireKey(key))); op.setRequestHash(hash);
        op.setAppointmentNo(root.getAppointmentNo()); op.setPreviewNo(preview.getPreviewNo());
        op.setAction(action); op.setState("PENDING"); op.setNextRetryAt(AppointmentPersistence.now());
        String commandAction = switch (action) { case "CREATE" -> "RESERVE"; case "CHANGE",
                "RECEPTION_CHANGE" -> "REPLACE";
            default -> "RELEASE"; };
        var request = new ResourceBookingPort.Accept(new ResourceBookingPort.Scope("MAIN", root.getEntityNo(),
                root.getStoreNo()), commandAction, operationNo + ":" + commandAction, "BEAUTY_APPOINTMENT",
                root.getAppointmentNo(), hold == null ? 0 : hold.ownerVersion(), root.getVersion(),
                hold == null ? null : new ResourceBookingPort.Target(hold.holdNo(), hold.version(),
                        hold.leaseRevision()), preview.getFrozenPlan());
        op.setAcceptPayload(db.json.writeValueAsString(request));
        db.operations.insert(op);
        var log = new BtyActionLog();
        log.setEntityNo(root.getEntityNo()); log.setStoreNo(root.getStoreNo());
        log.setActionNo(BizKey.next("BTAL")); log.setAggregateType("BEAUTY_APPOINTMENT");
        log.setAggregateNo(root.getAppointmentNo());
                log.setAction("RECEPTION_CHANGE".equals(action) ? "RECEPTION_CHANGE_PREPARED"
                : "APPOINTMENT_" + action + "_PREPARED");
        log.setOperatorRef(actor.operatorNo()); log.setExpectedRevision(root.getVersion());
        log.setResultRevision(root.getVersion()); log.setSafeSnapshot(requestEvidence);
        log.setRequestRef(op.getOperationNo()); db.actions.insert(log);
        var current = db.currents.selectScopedForUpdate(root.getEntityNo(), root.getStoreNo(),
                root.getAppointmentNo());
        current.setOperationNo(operationNo); AppointmentPersistence.require(db.currents.updateById(current)
                == 1);
        return op;
    }
    /** Commits a new reception root and its only resource command in the same transaction as the outer link. */
    public BtyAppointmentOperation prepareReception(String entity, String store, String actor, String number) {
        access.merchant(entity, store, actor, true);
        identities.authorize(entity, store, actor);
        var observed = db.read(() -> receptions.selectScoped(entity, store, number));
        AppointmentPersistence.require(observed != null && "RECEIVE_WALK_IN".equals(observed.getAction())
                && "BAPI-048".equals(observed.getEndpoint()));
        if (observed.getAppointmentOperationNo() != null) { return linkedReception(observed); }
        var payload = db.json.readValue(observed.getFrozenPayload(), WalkInPayload.class);
        var match = payload.matched();
        AppointmentPersistence.require(match != null && "MAIN".equals(match.scope().tenantNo())
                && entity.equals(match.scope().entityNo()) && store.equals(match.scope().storeNo()));
        var preview = new BtyAppointmentPreparation();
        preview.setEntityNo(entity); preview.setStoreNo(store); preview.setPreviewNo(payload.previewNo());
        var currentActor = access.actor();
        preview.setActorNo(actor); preview.setActorRealm(currentActor.realm());
        preview.setActorKind(currentActor.subjectKind());
        preview.setCustomerRef("USER".equals(observed.getIdentityKind()) ? observed.getIdentityRef() : null);
        preview.setGuestRef("GUEST".equals(observed.getIdentityKind()) ? observed.getIdentityRef() : null);
        preview.setExpectedRevision(0L); preview.setPolicyVersionNo(match.policyVersionNo());
        preview.setStartAt(match.startAt().toLocalDateTime()); preview.setEndAt(match.endAt().toLocalDateTime());
        preview.setTimezone("UTC"); preview.setFrozenPlan(match.plan());
        preview.setPlanHash(ResourcePlanContract.hash(match.plan()));
        preview.setSnapshot(db.json.writeValueAsString(match.snapshot()));
        preview.setExpiresAt(AppointmentPersistence.now().plusMinutes(5));
        for (var item : match.snapshot().items()) {
            var product = products.getObject().service(store, item.skuNo());
            AppointmentPersistence.require(entity.equals(product.store().entityNo())
                    && store.equals(product.store().storeNo()) && item.skuNo().equals(product.skuNo())
                    && item.goodsNo().equals(product.goodsNo())
                    && Objects.equals(item.goodsRevision(), product.goodsRevision())
                    && Objects.equals(item.skuRevision(), product.skuRevision())
                    && item.priceMinor() == product.priceMinor() && item.durationMin() == product.durationMin());
        }
        try {
            return db.tx(() -> {
                var root = createRoot(preview, payload.appointmentNo());
                var fence = db.fences.selectScopedForUpdate(entity, store, root.getAppointmentNo());
                var current = db.currents.selectScopedForUpdate(entity, store, root.getAppointmentNo());
                var outer = receptions.selectScopedForUpdate(entity, store, number);
                AppointmentPersistence.require(outer != null && "RECEIVED".equals(outer.getPhase())
                        && observed.getRequestHash().equals(outer.getRequestHash())
                        && observed.getFrozenPayload().equals(outer.getFrozenPayload())
                        && outer.getAppointmentNo() == null && outer.getAppointmentOperationNo() == null
                        && fence.getActiveIntentNo() == null && current.getOperationNo() == null);
                workOrders.verifyCreateLocked(match);
                db.preparations.insert(preview);
                var op = prepareLocked(preview, root, "CREATE", payload.appointmentKey(),
                        payload.appointmentRequestHash(), null, "{\"receptionOperationNo\":\"" + number + "\"}",
                        payload.appointmentOperationNo());
                outer.setAppointmentNo(root.getAppointmentNo()); outer.setAppointmentOperationNo(op.getOperationNo());
                outer.setPhase("RESOURCE_PENDING"); outer.setNextRetryAt(AppointmentPersistence.now());
                AppointmentPersistence.require(receptions.updateById(outer) == 1);
                // Head acquisition or earlier writes may wait; expiry must roll the entire preparation back.
                var at = clock.instant();
                if (ResourcePlanContract.decode(match.plan()).demands().stream()
                        .flatMap(d -> d.intervals().stream()).anyMatch(i -> !i.startAt().isAfter(at))) {
                    throw new ReceptionPlanExpired();
                }
                return op;
            });
        } catch (DuplicateKeyException raced) {
            var winner = db.read(() -> receptions.selectScoped(entity, store, number));
            if (winner == null || winner.getAppointmentOperationNo() == null) { throw raced; }
            return linkedReception(winner);
        }
    }
    /** Separates a rolled-back CREATE from ordinary conflicts so only expiry becomes reschedule-PENDING. */
    static final class ReceptionPlanExpired extends RuntimeException { }
    /** Internal binding is checked against durable source facts, never against the recovering employee's name. */
    public BtyAppointmentOperation linkedReception(BtyReceptionOperation outer) {
        var p = db.json.readValue(outer.getFrozenPayload(), WalkInPayload.class);
        var op = db.operation(outer.getAppointmentOperationNo());
        var request = db.json.readValue(op.getAcceptPayload(), ResourceBookingPort.Accept.class);
        var preview = db.preview(p.previewNo());
        var root = db.root(p.appointmentNo());
        AppointmentPersistence.require(root != null && p.matched() != null
                && outer.getTenantNo().equals(root.getTenantNo()) && outer.getEntityNo().equals(root.getEntityNo())
                && outer.getStoreNo().equals(root.getStoreNo())
                && outer.getTenantNo().equals(preview.getTenantNo())
                && outer.getEntityNo().equals(preview.getEntityNo()) && outer.getStoreNo().equals(preview.getStoreNo())
                && op.getActorNo().equals(preview.getActorNo()) && op.getActorRealm().equals(preview.getActorRealm())
                && op.getActorKind().equals(preview.getActorKind()) && preview.getExpectedRevision() == 0
                && preview.getFrozenPlan().equals(p.matched().plan())
                && preview.getPlanHash().equals(ResourcePlanContract.hash(p.matched().plan()))
                && Objects.equals(root.getCustomerRef(), preview.getCustomerRef())
                && Objects.equals(root.getGuestRef(), preview.getGuestRef())
                && ("USER".equals(outer.getIdentityKind())
                    ? outer.getIdentityRef().equals(preview.getCustomerRef()) && preview.getGuestRef() == null
                    : "GUEST".equals(outer.getIdentityKind()) && preview.getCustomerRef() == null
                        && outer.getIdentityRef().equals(preview.getGuestRef()))
                && outer.getTenantNo().equals(request.scope().tenantNo())
                && outer.getEntityNo().equals(request.scope().entityNo())
                && outer.getStoreNo().equals(request.scope().storeNo())
                && "BEAUTY_APPOINTMENT".equals(request.sourceType())
                && List.of("RESERVE", "CONFIRM").contains(request.action())
                && (op.getOperationNo() + ":" + request.action()).equals(request.key())
                && ("RESERVE".equals(request.action()) ? request.sourceRevision() == 1 : request.sourceRevision() > 1)
                && ("RESERVE".equals(request.action()) ? request.target() == null
                    : request.target() != null && Objects.equals(root.getHoldNo(), request.target().holdNo())));
        AppointmentPersistence.require("CREATE".equals(op.getAction())
                && outer.getTenantNo().equals(op.getTenantNo()) && outer.getEntityNo().equals(op.getEntityNo())
                && outer.getStoreNo().equals(op.getStoreNo()) && p.appointmentNo().equals(op.getAppointmentNo())
                && p.appointmentNo().equals(outer.getAppointmentNo()) && p.previewNo().equals(op.getPreviewNo())
                && p.appointmentOperationNo().equals(op.getOperationNo())
                && IdempotencyScope.requestHash(p.appointmentKey()).equals(op.getKeyHash())
                && p.appointmentRequestHash().equals(op.getRequestHash())
                && request.frozenPayload().equals(p.matched().plan())
                && request.sourceNo().equals(p.appointmentNo()));
        return op;
    }
    private BtyAppointment createRoot(BtyAppointmentPreparation p) {
        return createRoot(p, BizKey.next("BTA"));
    }
    private BtyAppointment createRoot(BtyAppointmentPreparation p, String number) {
        var root = new BtyAppointment();
        root.setEntityNo(p.getEntityNo()); root.setStoreNo(p.getStoreNo());
        root.setAppointmentNo(number); root.setCustomerRef(p.getCustomerRef());
                root.setGuestRef(p.getGuestRef());
        root.setSource("CONSUMER".equals(p.getActorRealm()) ? "ONLINE" : "STAFF"); root.setStatus("HELD");
        root.setStartAt(p.getStartAt()); root.setEndAt(p.getEndAt()); root.setTimezone(p.getTimezone());
        root.setPolicyVersionNo(p.getPolicyVersionNo()); root.setVersion(1L); db.roots.insert(root);
        var fence = new BtyAppointmentResourceFence(); fence.setEntityNo(root.getEntityNo());
        fence.setAppointmentNo(root.getAppointmentNo()); fence.setStoreNo(root.getStoreNo());
        fence.setFenceNo(BizKey.next("BTF")); db.fences.insert(fence);
        var current = new BtyAppointmentCurrent(); current.setEntityNo(root.getEntityNo());
        current.setAppointmentNo(root.getAppointmentNo()); current.setArrangementRevision(0L);
                db.currents.insert(current);
        return root;
    }
}
