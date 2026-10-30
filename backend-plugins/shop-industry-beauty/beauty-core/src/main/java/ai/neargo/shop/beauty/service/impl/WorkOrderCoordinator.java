package ai.neargo.shop.beauty.service.impl;

import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.beauty.dto.AppointmentAssignmentReq;
import ai.neargo.shop.beauty.dto.AppointmentAssignmentVO;
import ai.neargo.shop.beauty.dto.AppointmentItemReq;
import ai.neargo.shop.beauty.dto.OpenAppointmentWorkOrderReq;
import ai.neargo.shop.beauty.dto.OpenWorkOrderReq;
import ai.neargo.shop.beauty.dto.PublishStorePolicyVersionReq;
import ai.neargo.shop.beauty.dto.WalkInVO;
import ai.neargo.shop.beauty.dto.WorkOrderTaskSnapshotVO;
import ai.neargo.shop.beauty.dto.WorkOrderVO;
import ai.neargo.shop.beauty.entity.BtyActionLog;
import ai.neargo.shop.beauty.entity.BtyAppointment;
import ai.neargo.shop.beauty.entity.BtyAppointmentArrangement;
import ai.neargo.shop.beauty.entity.BtyReceptionOperation;
import ai.neargo.shop.beauty.entity.BtyServiceSubmission;
import ai.neargo.shop.beauty.entity.BtyServiceTask;
import ai.neargo.shop.beauty.entity.BtyWorkOrder;
import ai.neargo.shop.beauty.entity.BtyWorkOrderContext;
import ai.neargo.shop.beauty.mapper.BeautyMappers;
import ai.neargo.shop.beauty.policy.AppointmentAccess;
import ai.neargo.shop.beauty.policy.CustomerOwnership;
import ai.neargo.shop.beauty.policy.ReceptionAccess;
import ai.neargo.shop.beauty.policy.ReceptionIdentity;
import ai.neargo.shop.beauty.policy.ReceptionIdentity.VerifiedIdentity;
import ai.neargo.shop.beauty.service.impl.ReceptionPreparation.WalkInPayload;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.event.outbox.PublishOptions;
import ai.neargo.shop.idem.IdempotencyScope;
import ai.neargo.shop.spi.beauty.BeautyReceptionStateChanged;
import ai.neargo.shop.spi.resource.ResourceBookingPort;
import ai.neargo.shop.spi.resource.ResourcePlanContract;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;

/** All owner observations precede the one short atomic scope transaction. */
public final class WorkOrderCoordinator {
    private final AppointmentPersistence db;
    private final AppointmentAccess access;
    private final ReceptionAccess receptionAccess;
    private final ReceptionIdentity identities;
    private final CustomerOwnership ownership;
    private final ReceptionPreparation operations;
    private final WorkOrderPreparation preparation;
    private final ReceptionReassignment reassignment;
    private final AppointmentCoordinator appointments;
    private final BeautyMappers.ReceptionOperationMapper receptionRows;
    private final BeautyMappers.WorkOrderMapper orders;
    private final BeautyMappers.WorkOrderContextMapper contexts;
    private final BeautyMappers.ServiceTaskMapper tasks;
    private final BeautyMappers.ServiceSubmissionMapper submissions;
    private final OutboxPublisher publisher;
    private final Clock clock;
    public WorkOrderCoordinator(AppointmentPersistence db, AppointmentAccess access, ReceptionAccess receptionAccess,
            ReceptionIdentity identities, CustomerOwnership ownership, ReceptionPreparation operations,
            WorkOrderPreparation preparation,
            ReceptionReassignment reassignment, AppointmentCoordinator appointments,
            BeautyMappers.ReceptionOperationMapper receptionRows, BeautyMappers.WorkOrderMapper orders,
            BeautyMappers.WorkOrderContextMapper contexts, BeautyMappers.ServiceTaskMapper tasks,
            BeautyMappers.ServiceSubmissionMapper submissions, OutboxPublisher publisher, Clock clock) {
        this.db = db; this.access = access; this.receptionAccess = receptionAccess; this.identities = identities;
        this.ownership = ownership; this.operations = operations; this.preparation = preparation;
        this.reassignment = reassignment;
        this.appointments = appointments; this.receptionRows = receptionRows; this.orders = orders;
        this.contexts = contexts; this.tasks = tasks; this.submissions = submissions;
        this.publisher = publisher; this.clock = clock;
    }
    public WorkOrderVO openFromAppointment(String entity, String store, String actor, String number,
            OpenAppointmentWorkOrderReq req, String key, String endpoint) {
        authorize(entity, store, actor);
        if (req == null) { throw BizException.of(ErrorCode.BAD_REQUEST); }
        return openAppointment(entity, store, actor, number, req, key, endpoint,
                fingerprint(endpoint, entity, store, number, req), null);
    }
    public WorkOrderVO open(String entity, String store, String actor, OpenWorkOrderReq req, String key) {
        authorize(entity, store, actor);
        if (req == null || !store.equals(req.storeNo())) { throw BizException.of(ErrorCode.BAD_REQUEST); }
        if (req.appointmentNo() == null) { return openUnassignedAuthorized(entity, store, actor, req, key); }
        return openAppointment(entity, store, actor, req.appointmentNo(),
                new OpenAppointmentWorkOrderReq(req.expectedRevision(), req.assignments(), req.startAt()), key,
                "BAPI-054", fingerprint("BAPI-054", entity, store, req.appointmentNo(), req), req);
    }
    public WorkOrderVO openUnassigned(String entity, String store, String actor, OpenWorkOrderReq req, String key) {
        return open(entity, store, actor, req, key);
    }
    private WorkOrderVO openUnassignedAuthorized(String entity, String store, String actor, OpenWorkOrderReq req,
            String key) {
        var originalMemberScope =
                req.memberNo() == null ? null : receptionAccess.customer(entity, store, actor);
        String hash = fingerprint("BAPI-054", entity, store, null, req);
        var prior = operations.find(entity, store, actor, "BAPI-054", key, hash);
        if (succeeded(prior)) { return result(prior); }
        // Current identity owner failures remain outside the stale-scope recovery boundary.
        var identity = prior == null ? identity(entity, store, req) : canonical(prior);
        var op = prior;
        try {
            var prepared = preparation.unassigned(entity, store, identity, req.items());
            if (originalMemberScope != null) {
                authorize(entity, store, actor);
                // Preparation performs later owner IO; do not persist a new namespace under the
                // original hash.
                if (!originalMemberScope.equals(receptionAccess.customer(entity, store, actor))) {
                    throw BizException.of(ErrorCode.FORBIDDEN);
                }
            }
            String scopeHash = scopeHash(prepared);
            op =
                    prior == null
                            ? operations.begin(
                                    entity,
                                    store,
                                    actor,
                                    "BAPI-054",
                                    key,
                                    hash,
                                    identity,
                                    new OpenPayload(
                                            1,
                                            null,
                                            0,
                                            req.items(),
                                            List.of(),
                                            null,
                                            scopeHash,
                                            0L))
                            : prior;
            if (succeeded(op)) { return result(op); }
            AppointmentPersistence.require(payload(op).scopeHash().equals(scopeHash));
            return openPrepared(null, actor, op, prepared, 0);
        } catch (BizException stale) {
            return completedOrThrow(entity, store, actor, "BAPI-054", key, hash, op, stale);
        }
    }
    private WorkOrderVO openAppointment(String entity, String store, String actor, String number,
            OpenAppointmentWorkOrderReq req, String key, String endpoint, String hash, OpenWorkOrderReq full) {
        String canonicalHash = canonicalOpenHash(entity, store, number, req, key);
        var op =
                openAlias(
                        entity,
                        store,
                        actor,
                        number,
                        req,
                        key,
                        endpoint,
                        hash,
                        full,
                        canonicalHash);
        if (succeeded(op)) { return result(op); }
        if (op != null && "REJECTED".equals(op.getPhase())) { throw BizException.of(ErrorCode.CONFLICT); }
        if (op == null) {
            PreparedWorkOrderScope prepared;
            BtyAppointment observed;
            try {
                observed = scoped(entity, store, number);
                AppointmentPersistence.require(observed.getVersion().equals(req.expectedRevision())
                        && "CHECKED_IN".equals(observed.getStatus()) && observed.getCheckedInAt() != null);
                prepared = preparation.fromAppointment(observed);
            } catch (BizException stale) {
                return completedOpenOrThrow(
                        entity,
                        store,
                        actor,
                        number,
                        req,
                        key,
                        endpoint,
                        hash,
                        full,
                        canonicalHash,
                        null,
                        stale);
            }
            if (observed.getGuestRef() != null) {
                var verified = identities.guest(entity, store, observed.getGuestRef());
                prepared = preparedForIdentity(observed, prepared, verified);
            }
            // Explicit identity owner validation must not be swallowed by concurrency recovery.
            if (full != null) { verifyFull(entity, store, full, prepared); }
            operations.begin(
                    entity,
                    store,
                    actor,
                    "BAPI-052",
                    key,
                    canonicalHash,
                    prepared.identity(),
                    new OpenPayload(
                            2,
                            number,
                            req.expectedRevision(),
                            full == null ? List.of() : full.items(),
                            req.assignments(),
                            req.startAt(),
                            scopeHash(prepared),
                            req.expectedRevision(),
                            new OpenReplayEvidence(1, endpoint, hash, canonicalHash)));
            op =
                    openAlias(
                            entity,
                            store,
                            actor,
                            number,
                            req,
                            key,
                            endpoint,
                            hash,
                            full,
                            canonicalHash);
            AppointmentPersistence.require(op != null);
            if (succeeded(op)) { return result(op); }
        }
        try { return resumeAppointment(entity, store, actor, number, op);
        } catch (BizException stale) {
            return completedOpenOrThrow(
                    entity,
                    store,
                    actor,
                    number,
                    req,
                    key,
                    endpoint,
                    hash,
                    full,
                    canonicalHash,
                    op,
                    stale);
        }
    }

    private BtyReceptionOperation openAlias(
            String entity,
            String store,
            String actor,
            String number,
            OpenAppointmentWorkOrderReq req,
            String key,
            String endpoint,
            String fullHash,
            OpenWorkOrderReq full,
            String canonicalHash) {
        var aliases = operations.findOpenAliases(entity, store, actor, key);
        AppointmentPersistence.require(aliases.size() <= 1);
        if (aliases.isEmpty()) {
            return null;
        }
        var op = aliases.getFirst();
        var original = payload(op);
        AppointmentPersistence.require(
                number.equals(op.getAppointmentNo()) && number.equals(original.appointmentNo()));
        String immutableCanonical =
                canonicalOpenHash(
                        entity,
                        store,
                        number,
                        new OpenAppointmentWorkOrderReq(
                                original.expectedRevision(),
                                original.assignments(),
                                original.startAt()),
                        key);
        AppointmentPersistence.require(canonicalHash.equals(immutableCanonical));
        var evidence = original.replayEvidence();
        String origin = evidence == null ? op.getEndpoint() : evidence.originalEndpoint();
        String originalHash =
                evidence == null ? op.getRequestHash() : evidence.originalRequestHash();
        if (evidence != null) {
            AppointmentPersistence.require(
                    "BAPI-052".equals(op.getEndpoint())
                            && canonicalHash.equals(evidence.canonicalRequestHash())
                            && canonicalHash.equals(op.getRequestHash()));
        }
        if (origin.equals(endpoint)) {
            AppointmentPersistence.require(fullHash.equals(originalHash));
        } else if ("BAPI-054".equals(endpoint)) {
            AppointmentPersistence.require(
                    full != null
                            && full.items().isEmpty()
                            && full.customerRef() == null
                            && full.guestNo() == null);
        }
        return op;
    }

    private WorkOrderVO completedOpenOrThrow(
            String entity,
            String store,
            String actor,
            String number,
            OpenAppointmentWorkOrderReq req,
            String key,
            String endpoint,
            String fullHash,
            OpenWorkOrderReq full,
            String canonicalHash,
            BtyReceptionOperation original,
            BizException stale) {
        if (stale.errorCode() == ErrorCode.CONFLICT) {
            var completed =
                    openAlias(
                            entity,
                            store,
                            actor,
                            number,
                            req,
                            key,
                            endpoint,
                            fullHash,
                            full,
                            canonicalHash);
            if (succeeded(completed) && (original == null || sameOperation(original, completed))) {
                return result(completed);
            }
        }
        throw stale;
    }

    private String canonicalOpenHash(
            String entity,
            String store,
            String number,
            OpenAppointmentWorkOrderReq req,
            String key) {
        var user = SecurityUtils.requireUser();
        return IdempotencyScope.requestHash(
                "BEAUTY_APPOINTMENT_OPEN_V2",
                user.tenantNo(),
                entity,
                store,
                user.realm().name(),
                user.subjectKind().name(),
                user.userNo(),
                IdempotencyScope.requestHash(IdempotencyScope.requireKey(key)),
                number,
                Long.toString(req.expectedRevision()),
                db.json.writeValueAsString(req.assignments()),
                req.startAt() == null ? "null" : req.startAt().toInstant().toString());
    }

    private WorkOrderVO completedOrThrow(String entity, String store, String actor, String endpoint, String key,
            String hash, BtyReceptionOperation original, BizException stale) {
        if (stale.errorCode() == ErrorCode.CONFLICT) {
            var completed = operations.find(entity, store, actor, endpoint, key, hash);
            if (succeeded(completed) && (original == null || sameOperation(original, completed))) {
                return result(completed);
            }
        }
        throw stale;
    }
    private boolean sameOperation(BtyReceptionOperation expected, BtyReceptionOperation actual) {
        return actual != null && Objects.equals(expected.getTenantNo(), actual.getTenantNo())
                && Objects.equals(expected.getEntityNo(), actual.getEntityNo())
                && Objects.equals(expected.getStoreNo(), actual.getStoreNo())
                && Objects.equals(expected.getActorNo(), actual.getActorNo())
                && Objects.equals(expected.getActorRealm(), actual.getActorRealm())
                && Objects.equals(expected.getActorKind(), actual.getActorKind())
                && Objects.equals(expected.getEndpoint(), actual.getEndpoint())
                && Objects.equals(expected.getKeyHash(), actual.getKeyHash())
                && Objects.equals(expected.getRequestHash(), actual.getRequestHash())
                && Objects.equals(expected.getOperationNo(), actual.getOperationNo());
    }
    private WorkOrderVO resumeAppointment(String entity, String store, String actor, String number,
            BtyReceptionOperation op) {
        var original = payload(op);
        if (op.getAppointmentOperationNo() != null || !original.assignments().isEmpty() || original.startAt() != null) {
            var changed =
                    op.getAppointmentOperationNo() == null
                            ? reassignment.reassign(
                                    entity,
                                    store,
                                    actor,
                                    number,
                                    original.expectedRevision(),
                                    original.assignments(),
                                    original.startAt(),
                                    IdempotencyScope.requestHash(
                                            "BEAUTY_OPEN_REPLACE_V1", op.getDerivedKeyHash()),
                                    op.getOperationNo())
                            : appointments.advance(op.getAppointmentOperationNo());
            if (changed != null) {
                if ("REJECTED".equals(changed.getState())) {
                    reject(op); throw BizException.of(ErrorCode.CONFLICT);
                }
                if (!"SUCCEEDED".equals(changed.getState())) { return pending(op); }
                var receipt = db.json.readValue(changed.getReceipt(), ResourceBookingPort.Receipt.class);
                var accept = db.json.readValue(changed.getAcceptPayload(), ResourceBookingPort.Accept.class);
                var binding = receipt.binding();
                AppointmentPersistence.require(
                        "RECEPTION_CHANGE".equals(changed.getAction())
                                && number.equals(changed.getAppointmentNo())
                                && entity.equals(changed.getEntityNo())
                                && store.equals(changed.getStoreNo())
                                && IdempotencyScope.requestHash(
                                                IdempotencyScope.requestHash(
                                                        "BEAUTY_OPEN_REPLACE_V1",
                                                        op.getDerivedKeyHash()))
                                        .equals(changed.getKeyHash())
                                && IdempotencyScope.requestHash(
                                                "RECEPTION_CHANGE_V1",
                                                number,
                                                Long.toString(original.expectedRevision()),
                                                db.json.writeValueAsString(original.assignments()),
                                                db.json.writeValueAsString(original.startAt()))
                                        .equals(changed.getRequestHash())
                                && "SUCCEEDED".equals(receipt.status())
                                && "REPLACE".equals(binding.action())
                                && "MAIN".equals(binding.tenantNo())
                                && entity.equals(binding.entityNo())
                                && store.equals(binding.storeNo())
                                && number.equals(binding.sourceNo())
                                && "BEAUTY_APPOINTMENT".equals(binding.sourceType())
                                && binding.sourceRevision() == original.expectedRevision()
                                && accept.sourceRevision() == binding.sourceRevision()
                                && accept.frozenPayload().equals(receipt.frozenPayload())
                                && accept.expectedOwnerVersion() == receipt.expectedOwnerVersion()
                                && Objects.equals(accept.target(), receipt.target())
                                && ResourcePlanContract.hash(accept.frozenPayload())
                                        .equals(binding.planHash()));
                var opening = original.withOpeningRevision(binding.sourceRevision() + 1);
                final var active = op;
                var completed =
                        db.tx(
                                () -> {
                                    var root = db.lock(scoped(entity, store, number));
                                    db.fences.selectScopedForUpdate(entity, store, number);
                                    db.currents.selectScopedForUpdate(entity, store, number);
                                    var locked =
                                            receptionRows.selectScopedForUpdate(
                                                    entity, store, active.getOperationNo());
                                    AppointmentPersistence.require(sameOperation(active, locked));
                                    if (succeeded(locked)) {
                                        return result(locked);
                                    }
                                    AppointmentPersistence.require(
                                            changed.getOperationNo()
                                                    .equals(locked.getAppointmentOperationNo()));
                                    var persisted = payload(locked);
                                    if ("RESOURCE_PENDING".equals(locked.getPhase())) {
                                        AppointmentPersistence.require(
                                                persisted.equals(payload(active)));
                                        locked.setFrozenPayload(
                                                db.json.writeValueAsString(opening));
                                        locked.setPhase("OPEN_PENDING");
                                        AppointmentPersistence.require(
                                                receptionRows.updateById(locked) == 1);
                                    } else {
                                        AppointmentPersistence.require(
                                                "OPEN_PENDING".equals(locked.getPhase())
                                                        && persisted.equals(opening));
                                    }
                                    return null;
                                });
                if (completed != null) { return completed; }
                original = opening;
            }
        }
        var observed = scoped(entity, store, number);
        var prepared = preparedForIdentity(observed, preparation.fromAppointment(observed), canonical(op));
        if (op.getAppointmentOperationNo() == null && original.openingRevision() == original.expectedRevision()) {
            AppointmentPersistence.require(original.scopeHash().equals(scopeHash(prepared)));
        }
        return openPrepared(observed, actor, op, prepared, original.openingRevision());
    }
    /** Continue the original opening namespace using current authority and frozen canonical identity. */
    public WorkOrderVO recover(String entity, String store, String actor, String number) {
        authorize(entity, store, actor);
        var op = db.read(() -> receptionRows.selectScoped(entity, store, number));
        if (op == null) { throw BizException.of(ErrorCode.NOT_FOUND); }
        AppointmentPersistence.require("OPEN_WORK_ORDER".equals(op.getAction())
                && List.of("BAPI-052", "BAPI-054").contains(op.getEndpoint()));
        if (succeeded(op)) { return result(op); }
        AppointmentPersistence.require(!"REJECTED".equals(op.getPhase()));
        try {
            if (op.getAppointmentNo() != null) {
                return resumeAppointment(entity, store, actor, op.getAppointmentNo(), op);
            }
            var prepared = preparation.unassigned(entity, store, canonical(op), payload(op).items());
            AppointmentPersistence.require(payload(op).scopeHash().equals(scopeHash(prepared)));
            return openPrepared(null, actor, op, prepared, 0);
        } catch (BizException stale) {
            var winner = db.read(() -> receptionRows.selectScoped(entity, store, number));
            if (stale.errorCode() == ErrorCode.CONFLICT && succeeded(winner) && sameOperation(op, winner)) {
                return result(winner);
            }
            throw stale;
        }
    }
    /** The original 048 operation owns the same atomic writer as appointment opening. */
    public WorkOrderVO openReception(String entity, String store, String actor, String number) {
        authorize(entity, store, actor);
        var op = db.read(() -> receptionRows.selectScoped(entity, store, number));
        if (op == null) { throw BizException.of(ErrorCode.NOT_FOUND); }
        AppointmentPersistence.require("RECEIVE_WALK_IN".equals(op.getAction()) && "BAPI-048".equals(op.getEndpoint()));
        if (succeeded(op)) { return result(op); }
        var original = db.json.readValue(op.getFrozenPayload(), WalkInPayload.class);
        AppointmentPersistence.require("OPEN_PENDING".equals(op.getPhase()) && original.openingRevision() != null);
        var observed = scoped(entity, store, op.getAppointmentNo());
        var prepared = preparedForIdentity(observed, preparation.fromAppointment(observed), canonical(op));
        return openPrepared(observed, actor, op, prepared, original.openingRevision());
    }
    private WorkOrderVO openPrepared(BtyAppointment observed, String actor, BtyReceptionOperation op,
            PreparedWorkOrderScope prepared, long revision) {
        return db.tx(() -> {
            if (observed != null) {
                db.lock(observed);
                db.fences.selectScopedForUpdate(observed.getEntityNo(), observed.getStoreNo(),
                        observed.getAppointmentNo());
                db.currents.selectScopedForUpdate(observed.getEntityNo(), observed.getStoreNo(),
                        observed.getAppointmentNo());
            }
            var locked = receptionRows.selectScopedForUpdate(prepared.scope().entityNo(), prepared.scope().storeNo(),
                    op.getOperationNo());
            AppointmentPersistence.require(sameOperation(op, locked));
            if (succeeded(locked)) { return result(locked); }
            if ("RECEIVE_WALK_IN".equals(locked.getAction())) {
                var original = db.json.readValue(locked.getFrozenPayload(), WalkInPayload.class);
                AppointmentPersistence.require("BAPI-048".equals(locked.getEndpoint())
                        && "OPEN_PENDING".equals(locked.getPhase()) && original.openingRevision() == revision
                        && original.appointmentNo().equals(locked.getAppointmentNo())
                        && original.appointmentOperationNo().equals(locked.getAppointmentOperationNo()));
            }
            boolean walkIn = "RECEIVE_WALK_IN".equals(locked.getAction());
            if (observed != null) { verifyAppointmentLocked(observed, prepared, revision, walkIn); }
            AppointmentPersistence.require(!"REJECTED".equals(locked.getPhase()));
            preparation.verifyLocked(prepared);
            // This check follows all lock acquisition: owner reads or lock waits may cross the frozen start.
            if ("RECEIVE_WALK_IN".equals(locked.getAction())) {
                var original = db.json.readValue(locked.getFrozenPayload(), WalkInPayload.class);
                var at = clock.instant();
                boolean future = ResourcePlanContract.decode(original.matched().plan()).demands().stream()
                        .flatMap(d -> d.intervals().stream()).allMatch(i -> i.startAt().isAfter(at));
                if (!future) {
                    locked.setFailureCode("ARRIVAL_REQUIRES_RESCHEDULE");
                    AppointmentPersistence.require(receptionRows.updateById(locked) == 1);
                    receptionRows.update(null, Wrappers.<BtyReceptionOperation>lambdaUpdate()
                            .eq(BtyReceptionOperation::getId, locked.getId())
                            .set(BtyReceptionOperation::getNextRetryAt, null)
                            .set(BtyReceptionOperation::getLeaseUntil, null));
                    return pending(locked);
                }
            }
            if (observed != null) {
                AppointmentPersistence.require(db.roots.markWorkOrderOpened(observed.getEntityNo(),
                        observed.getStoreNo(), observed.getAppointmentNo(), revision, actor, now()) == 1);
            }
            return persist(locked, prepared);
        });
    }
    private void verifyAppointmentLocked(BtyAppointment observed, PreparedWorkOrderScope prepared, long revision,
            boolean walkIn) {
        var root = db.lock(observed);
        var fence = db.fences.selectScopedForUpdate(observed.getEntityNo(), observed.getStoreNo(),
                observed.getAppointmentNo());
        var current = db.currents.selectScopedForUpdate(observed.getEntityNo(), observed.getStoreNo(),
                observed.getAppointmentNo());
        var evidence = prepared.frozen().holdEvidence();
        AppointmentPersistence.require(root != null && fence != null && current != null
                && fence.getActiveIntentNo() == null && current.getOperationNo() == null
                && root.getVersion() == revision && "CHECKED_IN".equals(root.getStatus())
                && root.getCheckedInAt() != null && (walkIn || now().isBefore(root.getEndAt()))
                && Objects.equals(root.getHoldNo(), observed.getHoldNo())
                && Objects.equals(root.getStartAt(), observed.getStartAt())
                && Objects.equals(root.getEndAt(), observed.getEndAt())
                && Objects.equals(root.getCustomerRef(), observed.getCustomerRef())
                && Objects.equals(root.getGuestRef(), observed.getGuestRef())
                && current.getArrangementRevision() == evidence.arrangementRevision());
        AppointmentPersistence.require(db.roots.countWorkOrders(root.getEntityNo(), root.getStoreNo(),
                root.getAppointmentNo()) == 0);
        var row = db.arrangements.selectOne(Wrappers.<BtyAppointmentArrangement>lambdaQuery()
                .eq(BtyAppointmentArrangement::getTenantNo, root.getTenantNo())
                .eq(BtyAppointmentArrangement::getEntityNo, root.getEntityNo())
                .eq(BtyAppointmentArrangement::getAppointmentNo, root.getAppointmentNo())
                .eq(BtyAppointmentArrangement::getArrangementRevision, evidence.arrangementRevision()));
        AppointmentPersistence.require(row != null && root.getHoldNo().equals(evidence.hold().holdNo())
                && evidence.arrangementHash().equals(IdempotencyScope.requestHash(row.getPreviewNo(),
                        row.getHoldNo(), row.getSnapshot())));
    }
    private WorkOrderVO persist(BtyReceptionOperation op, PreparedWorkOrderScope prepared) {
        var scope = prepared.scope();
        var root = new BtyWorkOrder(); root.setEntityNo(scope.entityNo()); root.setStoreNo(scope.storeNo());
        root.setWorkOrderNo(BizKey.next("BTWO")); root.setAppointmentNo(op.getAppointmentNo());
        root.setCustomerRef(prepared.identity().kind() + ":" + prepared.identity().reference());
        root.setStatus("OPEN");
        root.setScopeRevision(1L); root.setScopeFrozen(false); orders.insert(root);
        List<TaskReference> refs = new ArrayList<>();
        for (int i = 0; i < prepared.frozen().items().size(); i++) {
            var item = prepared.frozen().items().get(i);
            var assignments = prepared.assignments().stream().filter(a ->
                    Objects.equals(item.appointmentItemNo(), a.appointmentItemNo())).toList();
            var task = new BtyServiceTask(); task.setEntityNo(scope.entityNo());
            task.setTaskNo(BizKey.next("BTST"));
            task.setWorkOrderNo(root.getWorkOrderNo()); task.setScopeRevision(1L); task.setSkuNo(item.skuNo());
            task.setDefinitionVersionNo(item.definitionVersionNo()); task
                    .setAppointmentItemNo(item.appointmentItemNo());
            task.setQty(BigDecimal.valueOf(item.qty())); task.setDoneQty(BigDecimal.ZERO);
            task.setVoidedQty(BigDecimal.ZERO); task.setUom("COUNT"); task.setStatus("PENDING");
            task.setBindingRevision(0L);
            task.setExecutionSnapshot(
                    db.json.writeValueAsString(
                            new TaskExecutionSnapshot(
                                    1,
                                    op.getAppointmentNo() == null ? "UNASSIGNED" : "ASSIGNED",
                                    prepared.frozen().definitions().get(i),
                                    assignments,
                                    item.serviceStartAt(),
                                    item.serviceEndAt())));
            tasks.insert(task); refs.add(new TaskReference(task.getTaskNo(), task.getSkuNo(),
                    task.getDefinitionVersionNo(), task.getQty(), task.getUom()));
        }
        String hash = scopeHash(prepared);
        var context = new BtyWorkOrderContext(); context.setEntityNo(scope.entityNo()); context
                .setStoreNo(scope.storeNo());
        context.setWorkOrderNo(root.getWorkOrderNo()); context.setIdentityKind(prepared.identity().kind());
        context.setIdentityRef(prepared.identity().reference()); context.setSourceReceptionOperationNo(op
                .getOperationNo());
        context.setAppointmentArrangementRevision(prepared.frozen().holdEvidence() == null ? null
                : prepared.frozen().holdEvidence().arrangementRevision());
        context.setFrozenScope(db.json.writeValueAsString(new WorkOrderScopeSnapshot(1, 1, hash,
                prepared.frozen().policyVersionNo(), prepared.frozen().cancelRule(), prepared.frozen(),
                prepared.assignments(), refs))); contexts.insert(context);
        var submission = new BtyServiceSubmission(); submission.setEntityNo(scope.entityNo());
        submission.setStoreNo(scope.storeNo()); submission.setWorkOrderNo(root.getWorkOrderNo());
        submission.setSubmissionNo(BizKey.next("BTSU"));
        submission.setAction("OPEN");
        submission.setStatus("RECEIVED");
        submission.setRequestKeyHash(op.getDerivedKeyHash());
        String draft = db.json.writeValueAsString(new OpenSubmissionDraft(1, 1, hash, prepared.identity(), refs));
        submission.setFrozenCommand(draft); submission.setPayloadHash(IdempotencyScope.requestHash(draft));
        submissions.insert(submission);
        var result = new WorkOrderVO(root.getWorkOrderNo(), root.getAppointmentNo(), 1, "OPEN",
                submission.getSubmissionNo(), "UNBOUND", op.getOperationNo(), "SUCCEEDED",
                refs.stream().map(TaskReference::taskNo).toList(), prepared.frozen().items()
                        .getFirst().serviceStartAt());
        var log = new BtyActionLog(); log.setEntityNo(scope.entityNo()); log.setStoreNo(scope.storeNo());
        log.setActionNo(BizKey.next("BTAL")); log.setAggregateType("BEAUTY_WORK_ORDER");
        log.setAggregateNo(root.getWorkOrderNo()); log.setAction("WORK_ORDER_OPENED");
        log.setRequestRef(op.getOperationNo()); log.setOperatorRef(SecurityUtils.requireUser().userNo());
        log.setExpectedRevision(0L); log.setResultRevision(1L);
        log.setSafeSnapshot(db.json.writeValueAsString(new SafeScope("OPEN", 1, result.taskNos())));
        db.actions.insert(log);
        publisher.publish(new BeautyReceptionStateChanged(root.getWorkOrderNo(), scope.entityNo(), scope.storeNo(),
                1, "OPEN"), new PublishOptions("BEAUTY:RECEPTION:" + op.getOperationNo(), 1));
        op.setPhase("SUCCEEDED"); op.setWorkOrderNo(root.getWorkOrderNo());
        op.setResult(db.json.writeValueAsString("RECEIVE_WALK_IN".equals(op.getAction())
                ? new WalkInVO(op.getOperationNo(), "SUCCEEDED", op.getAppointmentNo(), result,
                        db.json.readValue(op.getFrozenPayload(), WalkInPayload.class).selectedStartAt(), null)
                : result));
        AppointmentPersistence.require(receptionRows.updateById(op) == 1);
        return result;
    }
    private PreparedWorkOrderScope preparedForIdentity(BtyAppointment root, PreparedWorkOrderScope prepared,
            VerifiedIdentity canonical) {
        boolean same = prepared.identity().equals(canonical);
        AppointmentPersistence.require(same || root.getGuestRef() != null && "USER".equals(canonical.kind())
                && ownership.owns(root, canonical.reference()));
        return new PreparedWorkOrderScope(canonical, prepared.frozen(), prepared.assignments(), prepared.scope());
    }
    private void verifyFull(String entity, String store, OpenWorkOrderReq req, PreparedWorkOrderScope prepared) {
        if (req.customerRef() != null || req.guestNo() != null) {
            AppointmentPersistence.require(identity(entity, store, req).equals(prepared.identity()));
        }
        if (!req.items().isEmpty()) {
            var requested = req.items().stream().flatMap(item -> IntStream.range(0, item.qty())
                    .mapToObj(i -> item.skuNo())).toList();
            AppointmentPersistence.require(requested.equals(prepared.frozen().items().stream()
                    .map(item -> item.skuNo()).toList()));
        }
    }
    private VerifiedIdentity identity(String entity, String store, OpenWorkOrderReq req) {
        if (req.memberNo() != null) {
            return new VerifiedIdentity(
                    "USER", identities.customerMember(entity, store, req.memberNo()));
        }
        return req.customerRef() != null
                ? new VerifiedIdentity(
                        "USER", identities.customer(entity, store, req.customerRef()))
                : identities.guest(entity, store, req.guestNo());
    }
    private void authorize(String entity, String store, String actor) {
        access.merchant(entity, store, actor, true); receptionAccess.customer(entity, store, actor);
    }
    private BtyAppointment scoped(String entity, String store, String number) {
        var root = db.root(number);
        if (root == null || !entity.equals(root.getEntityNo()) || !store.equals(root.getStoreNo())) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return root;
    }
    private String fingerprint(String endpoint, String entity, String store, String number, Object req) {
        var user = SecurityUtils.requireUser();
        return IdempotencyScope.requestHash(
                "BEAUTY_RECEPTION_V1",
                endpoint,
                user.tenantNo(),
                entity,
                store,
                user.realm().name(),
                user.subjectKind().name(),
                user.userNo(),
                number == null ? "UNASSIGNED" : number,
                db.json.writeValueAsString(req));
    }
    private String scopeHash(PreparedWorkOrderScope prepared) {
        List<ScopeLine> lines = new ArrayList<>();
        for (int i = 0; i < prepared.frozen().items().size(); i++) {
            var item = prepared.frozen().items().get(i);
            lines.add(
                    new ScopeLine(
                            i,
                            item.appointmentItemNo(),
                            item.skuNo(),
                            item.definitionVersionNo(),
                            item.qty(),
                            "COUNT",
                            prepared.frozen().definitions().get(i),
                            item.serviceStartAt(),
                            item.serviceEndAt()));
        }
        // Observation heads and hold journal revisions are checked separately; they do not change
        // service semantics.
        return IdempotencyScope.requestHash(
                "BEAUTY_WORK_ORDER_SCOPE_V1",
                db.json.writeValueAsString(
                        new ScopeSemantic(
                                1,
                                prepared.scope(),
                                prepared.identity(),
                                1,
                                lines,
                                prepared.frozen().policyVersionNo(),
                                prepared.frozen().cancelRule(),
                                prepared.assignments())));
    }
    private record ScopeLine(int lineIndex, String appointmentItemNo, String skuNo, String definitionVersionNo,
            int qty, String uom, WorkOrderTaskSnapshotVO definition, OffsetDateTime plannedStartAt,
            OffsetDateTime plannedEndAt) { }
    private record ScopeSemantic(int schemaVersion, ResourceBookingPort.Scope scope, VerifiedIdentity identity,
            long scopeRevision, List<ScopeLine> lines, String policyVersionNo,
            PublishStorePolicyVersionReq.CancellationRule cancelRule, List<AppointmentAssignmentVO> assignments) { }
    private VerifiedIdentity canonical(BtyReceptionOperation op) {
        return new VerifiedIdentity(op.getIdentityKind(), op.getIdentityRef());
    }

    private OpenPayload payload(BtyReceptionOperation op) {
        try {
            var value = db.json.readValue(op.getFrozenPayload(), OpenPayload.class);
            AppointmentPersistence.require(
                    value != null
                            && (value.schemaVersion() == 1 || value.schemaVersion() == 2)
                            && value.items() != null
                            && value.assignments() != null
                            && value.openingRevision() != null
                            && value.scopeHash() != null
                            && value.scopeHash().matches("[a-f0-9]{64}"));
            if (value.schemaVersion() == 2) {
                var evidence = value.replayEvidence();
                AppointmentPersistence.require(
                        value.appointmentNo() != null
                                && evidence != null
                                && evidence.schemaVersion() == 1
                                && List.of("BAPI-052", "BAPI-054")
                                        .contains(evidence.originalEndpoint())
                                && evidence.originalRequestHash() != null
                                && evidence.originalRequestHash().matches("[a-f0-9]{64}")
                                && evidence.canonicalRequestHash() != null
                                && evidence.canonicalRequestHash().matches("[a-f0-9]{64}"));
            } else {
                AppointmentPersistence.require(value.replayEvidence() == null);
            }
            return value;
        } catch (BizException failure) {
            throw failure;
        } catch (RuntimeException malformed) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }

    private boolean succeeded(BtyReceptionOperation op) { return op != null && "SUCCEEDED".equals(op.getPhase()); }
    private WorkOrderVO result(BtyReceptionOperation op) {
        if ("RECEIVE_WALK_IN".equals(op.getAction())) {
            return db.json.readValue(op.getResult(), WalkInVO.class).workOrder();
        }
        AppointmentPersistence.require("OPEN_WORK_ORDER".equals(op.getAction()));
        return db.json.readValue(op.getResult(), WorkOrderVO.class);
    }
    private WorkOrderVO pending(BtyReceptionOperation op) {
        return new WorkOrderVO(
                null,
                op.getAppointmentNo(),
                0,
                "PENDING",
                null,
                "UNBOUND",
                op.getOperationNo(),
                "PENDING",
                List.of(),
                null);
    }
    private void reject(BtyReceptionOperation op) {
        db.tx(
                () -> {
                    var row =
                            receptionRows.selectScopedForUpdate(
                                    op.getEntityNo(), op.getStoreNo(), op.getOperationNo());
                    row.setPhase("REJECTED");
                    row.setFailureCode("CONFLICT");
                    receptionRows.updateById(row);
                    return null;
                });
    }
    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).withNano(0); }

    public record OpenPayload(
            int schemaVersion,
            String appointmentNo,
            long expectedRevision,
            List<AppointmentItemReq> items,
            List<AppointmentAssignmentReq> assignments,
            OffsetDateTime startAt,
            String scopeHash,
            Long openingRevision,
            OpenReplayEvidence replayEvidence)
            implements ReceptionPreparation.ReceptionPayload {
        /**
         * Existing schema1 callers retain their original payload and resource recovery namespace.
         */
        public OpenPayload(
                int schemaVersion,
                String appointmentNo,
                long expectedRevision,
                List<AppointmentItemReq> items,
                List<AppointmentAssignmentReq> assignments,
                OffsetDateTime startAt,
                String scopeHash,
                Long openingRevision) {
            this(
                    schemaVersion,
                    appointmentNo,
                    expectedRevision,
                    items,
                    assignments,
                    startAt,
                    scopeHash,
                    openingRevision,
                    null);
        }

        OpenPayload withOpeningRevision(long value) {
            return new OpenPayload(
                    schemaVersion,
                    appointmentNo,
                    expectedRevision,
                    items,
                    assignments,
                    startAt,
                    scopeHash,
                    value,
                    replayEvidence);
        }
    }

    private record OpenReplayEvidence(
            int schemaVersion,
            String originalEndpoint,
            String originalRequestHash,
            String canonicalRequestHash) {}

    public record TaskExecutionSnapshot(int schemaVersion, String assignmentState, WorkOrderTaskSnapshotVO taskSnapshot,
            List<AppointmentAssignmentVO> assignments, OffsetDateTime plannedStartAt, OffsetDateTime plannedEndAt) { }
    public record TaskReference(String taskNo, String skuNo, String definitionVersionNo, BigDecimal qty, String uom) { }
    public record WorkOrderScopeSnapshot(int schemaVersion, long originalScopeRevision, String scopeHash,
            String policyVersionNo, PublishStorePolicyVersionReq.CancellationRule cancelRule,
                    FrozenAppointmentScope frozen,
            List<AppointmentAssignmentVO> assignments, List<TaskReference> taskRefs) { }
    public record OpenSubmissionDraft(int schemaVersion, long scopeRevision, String scopeHash,
            VerifiedIdentity identity,
            List<TaskReference> taskRefs) { }
    private record SafeScope(String state, long scopeRevision, List<String> taskNos) { }
}
