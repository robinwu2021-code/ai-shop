package ai.neargo.shop.beauty.service.impl;

import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.beauty.dto.AppointmentAssignmentReq;
import ai.neargo.shop.beauty.dto.ArrivalVO;
import ai.neargo.shop.beauty.dto.CheckInReq;
import ai.neargo.shop.beauty.dto.MyArrivalVO;
import ai.neargo.shop.beauty.dto.MyCheckInReq;
import ai.neargo.shop.beauty.dto.ReceiveWalkInReq;
import ai.neargo.shop.beauty.dto.ReceptionExceptionReq;
import ai.neargo.shop.beauty.dto.ReceptionExceptionVO;
import ai.neargo.shop.beauty.dto.WalkInVO;
import ai.neargo.shop.beauty.entity.BtyActionLog;
import ai.neargo.shop.beauty.entity.BtyAppointment;
import ai.neargo.shop.beauty.entity.BtyAppointmentArrangement;
import ai.neargo.shop.beauty.entity.BtyAppointmentOperation;
import ai.neargo.shop.beauty.entity.BtyArrivalChallenge;
import ai.neargo.shop.beauty.entity.BtyReceptionOperation;
import ai.neargo.shop.beauty.mapper.BeautyMappers;
import ai.neargo.shop.beauty.policy.AppointmentAccess;
import ai.neargo.shop.beauty.policy.CustomerOwnership;
import ai.neargo.shop.beauty.policy.ReceptionAccess;
import ai.neargo.shop.beauty.policy.ReceptionIdentity.VerifiedIdentity;
import ai.neargo.shop.beauty.policy.ReceptionIdentity;
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
import ai.neargo.svc.client.CallOutcome;
import ai.neargo.svc.client.ServiceCallException;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

/** Arrival observes owner proof outside locks and commits only local presence/audit facts under the root lock. */
public final class ReceptionCoordinator {
    private final AppointmentPersistence db;
    private final AppointmentAccess access;
    private final ReceptionAccess receptionAccess;
    private final ReceptionIdentity identities;
    private final CustomerOwnership ownership;
    private final ReceptionPreparation preparations;
    private final AppointmentCoordinator appointments;
    private final BeautyMappers.ReceptionOperationMapper operations;
    private final BeautyMappers.ArrivalChallengeMapper challenges;
    private final OutboxPublisher publisher;
    private final Clock clock;
    private final ReceptionReassignment reassignment;
    private final AppointmentMatching matching;
    private final AppointmentPreparation appointmentPreparation;
    private final WorkOrderCoordinator workOrders;
    public ReceptionCoordinator(AppointmentPersistence db, AppointmentAccess access, ReceptionAccess receptionAccess,
            ReceptionIdentity identities, CustomerOwnership ownership, ReceptionPreparation preparations,
            AppointmentCoordinator appointments, BeautyMappers.ReceptionOperationMapper operations,
            BeautyMappers.ArrivalChallengeMapper challenges, OutboxPublisher publisher, Clock clock,
            ReceptionReassignment reassignment, AppointmentMatching matching,
            AppointmentPreparation appointmentPreparation, WorkOrderCoordinator workOrders) {
        this.db = db; this.access = access; this.receptionAccess = receptionAccess; this.identities = identities;
        this.ownership = ownership; this.preparations = preparations; this.appointments = appointments;
        this.operations = operations; this.challenges = challenges; this.publisher = publisher; this.clock = clock;
        this.reassignment = reassignment; this.matching = matching;
        this.appointmentPreparation = appointmentPreparation; this.workOrders = workOrders;
    }
    /** A verified identity and stable operation are durable before any resource command is accepted. */
    public WalkInVO receiveWalkIn(String entity, String store, String actor, ReceiveWalkInReq req, String key) {
        access.merchant(entity, store, actor, true); receptionAccess.customer(entity, store, actor);
        if (req == null || !store.equals(req.storeNo())) { throw BizException.of(ErrorCode.BAD_REQUEST); }
        String hash = fingerprint("BAPI-048", entity, store, req);
        var op = preparations.find(entity, store, actor, "BAPI-048", key, hash);
        if (op == null) {
            var identity = req.customerRef() == null ? identities.guest(entity, store, req.guestNo())
                    : new VerifiedIdentity("USER", identities.customer(entity, store, req.customerRef()));
            op = preparations.begin(entity, store, actor, "BAPI-048", key, hash, identity,
                    new WalkInPayload(1, BizKey.next("BTA"), BizKey.next("BTPR"), BizKey.next("BTOP"),
                            req.items(), req.assignments(), req.startAt(), null, null,
                            IdempotencyScope.requestHash("BEAUTY_WALK_IN_CREATE_V1",
                                    ReceptionPreparation.derived(entity, store, "BAPI-048", key)), null, null, null));
        }
        return resumeWalkIn(entity, store, actor, op);
    }
    /** Current permissions authorize continuation; the original actor remains an immutable audit fact. */
    public WalkInVO recoverWalkIn(String entity, String store, String actor, String number) {
        access.merchant(entity, store, actor, true); receptionAccess.customer(entity, store, actor);
        return resumeWalkIn(entity, store, actor, walkInOperation(entity, store, number));
    }
    private WalkInVO resumeWalkIn(String entity, String store, String actor, BtyReceptionOperation observed) {
        var op = observed;
        try {
            if (succeeded(op) || "REJECTED".equals(op.getPhase())) { return walkInView(op); }
            var payload = walkInPayload(op);
            if (payload.matched() == null) {
                var scope = matching.scope(store);
                AppointmentPersistence.require(entity.equals(scope.entityNo()));
                op = preparations.freezeWalkIn(op, matching.matchWalkIn(scope, payload.items(), payload.assignments(),
                        payload.requestedStartAt(), clock.instant()));
                payload = walkInPayload(op);
            }
            if (succeeded(op)) { return walkInView(op); }
            // Expiry forbids new effects, but never prevents reading an already accepted command's outcome.
            if ("RESOURCE_PENDING".equals(op.getPhase())) {
                var accepted = appointmentPreparation.linkedReception(op);
                if (accepted.getReceipt() != null && "PENDING".equals(accepted.getState())) {
                    appointments.recover(accepted);
                    accepted = appointmentPreparation.linkedReception(op);
                }
                if ("RELEASE_REQUIRED".equals(accepted.getState())) { return closeReleasedWalkIn(op); }
                if ("REJECTED".equals(accepted.getState())) { return rejectWalkIn(op); }
            }
            if (!future(payload)) { return walkInFailure(op, "ARRIVAL_REQUIRES_RESCHEDULE"); }
            if ("RECEIVED".equals(op.getPhase())) {
                try { appointmentPreparation.prepareReception(entity, store, actor, op.getOperationNo()); }
                catch (AppointmentPreparation.ReceptionPlanExpired expired) {
                    return walkInFailure(op, "ARRIVAL_REQUIRES_RESCHEDULE");
                }
                op = walkInOperation(entity, store, op.getOperationNo());
            }
            if ("RESOURCE_PENDING".equals(op.getPhase())) {
                var linked = appointmentPreparation.linkedReception(op);
                if ("PENDING".equals(linked.getState())) { linked = appointments.advance(linked.getOperationNo()); }
                if ("RELEASE_REQUIRED".equals(linked.getState())) {
                    return closeReleasedWalkIn(op);
                }
                if ("REJECTED".equals(linked.getState())) { return rejectWalkIn(op); }
                if (!"SUCCEEDED".equals(linked.getState())) { return walkInView(op); }
                final var active = op;
                op = db.tx(() -> {
                    var root = db.lock(scoped(entity, store, active.getAppointmentNo()));
                    var fence = db.fences.selectScopedForUpdate(entity, store, root.getAppointmentNo());
                    var current = db.currents.selectScopedForUpdate(entity, store, root.getAppointmentNo());
                    var locked = operations.selectScopedForUpdate(entity, store, active.getOperationNo());
                    if (!"RESOURCE_PENDING".equals(locked.getPhase())) { return locked; }
                    var original = appointmentPreparation.linkedReception(locked);
                    var receipt = appointments.verifiedReceipt(original, true);
                    AppointmentPersistence.require("SUCCEEDED".equals(receipt.status())
                            && "CONFIRM".equals(receipt.binding().action()) && "SUCCEEDED".equals(original.getState())
                            && "CONFIRMED".equals(root.getStatus()) && root.getHoldNo() != null
                            && fence.getActiveIntentNo() == null && current.getOperationNo() == null);
                    locked.setFrozenPayload(db.json.writeValueAsString(walkInPayload(locked)
                            .withRevisions(root.getVersion(), null)));
                    locked.setPhase("ARRIVAL_PENDING");
                    AppointmentPersistence.require(operations.updateById(locked) == 1);
                    clearRetry(locked);
                    return locked;
                });
            }
            if ("ARRIVAL_PENDING".equals(op.getPhase())) { op = arriveWalkIn(op, actor); }
            if ("OPEN_PENDING".equals(op.getPhase())) {
                workOrders.openReception(entity, store, actor, op.getOperationNo());
                op = walkInOperation(entity, store, op.getOperationNo());
            }
            return walkInView(op);
        } catch (BizException stale) {
            if (stale.errorCode() == ErrorCode.CONFLICT) {
                var winner = walkInOperation(entity, store, observed.getOperationNo());
                if (succeeded(winner) && sameWalkIn(observed, winner)) { return walkInView(winner); }
            }
            throw stale;
        }
    }
    private BtyReceptionOperation arriveWalkIn(BtyReceptionOperation observed, String actor) {
        var original = scoped(observed.getEntityNo(), observed.getStoreNo(), observed.getAppointmentNo());
        var evidence = appointments.currentArrangementEvidence(original);
        return db.tx(() -> {
            var root = db.lock(original);
            var fence = db.fences.selectScopedForUpdate(root.getEntityNo(), root.getStoreNo(), root.getAppointmentNo());
            var current = db.currents.selectScopedForUpdate(root.getEntityNo(), root.getStoreNo(),
                    root.getAppointmentNo());
            var op = operations.selectScopedForUpdate(root.getEntityNo(), root.getStoreNo(), observed.getOperationNo());
            AppointmentPersistence.require(sameWalkIn(observed, op));
            if (!"ARRIVAL_PENDING".equals(op.getPhase())) { return op; }
            var payload = walkInPayload(op);
            AppointmentPersistence.require(payload.arrivalRevision() != null
                    && root.getVersion().equals(payload.arrivalRevision()) && "CONFIRMED".equals(root.getStatus())
                    && root.getCheckedInAt() == null && fence.getActiveIntentNo() == null
                    && current.getOperationNo() == null
                    && current.getArrangementRevision() == evidence.arrangementRevision()
                    && db.roots.countWorkOrders(root.getEntityNo(), root.getStoreNo(), root.getAppointmentNo()) == 0
                    && future(payload) && now().isBefore(root.getEndAt()));
            appointmentPreparation.linkedReception(op);
            verifyArrangement(root, evidence);
            AppointmentPersistence.require(db.roots.markArrived(root.getEntityNo(), root.getStoreNo(),
                    root.getAppointmentNo(), root.getVersion(), "CONFIRMED", now(), actor, now()) == 1);
            op.setFrozenPayload(db.json.writeValueAsString(payload.withRevisions(payload.arrivalRevision(),
                    root.getVersion() + 1)));
            op.setPhase("OPEN_PENDING"); AppointmentPersistence.require(operations.updateById(op) == 1);
            action(root, op, "CHECKED_IN", null, "{\"state\":\"CHECKED_IN\"}", root.getVersion() + 1);
            publish(root, op, root.getVersion() + 1, "CHECKED_IN");
            return op;
        });
    }
    private WalkInVO closeReleasedWalkIn(BtyReceptionOperation observed) {
        var linked = appointmentPreparation.linkedReception(observed);
        var request = db.json.readValue(linked.getAcceptPayload(), ResourceBookingPort.Accept.class);
        AppointmentPersistence.require(request.target() != null && "CONFIRM".equals(request.action()));
        String holdNo = request.target().holdNo();
        var release = db.read(() -> db.operations.selectOne(Wrappers.<BtyAppointmentOperation>lambdaQuery()
                .eq(BtyAppointmentOperation::getTenantNo, observed.getTenantNo())
                .eq(BtyAppointmentOperation::getEntityNo, observed.getEntityNo())
                .eq(BtyAppointmentOperation::getStoreNo, observed.getStoreNo())
                .eq(BtyAppointmentOperation::getAppointmentNo, observed.getAppointmentNo())
                .eq(BtyAppointmentOperation::getAction, "CANCEL").eq(BtyAppointmentOperation::getState, "SUCCEEDED")
                .orderByDesc(BtyAppointmentOperation::getId).last("LIMIT 1")));
        if (release == null) { return walkInFailure(observed, "RESOURCE_RELEASE_REQUIRED"); }
        try { appointments.releasedHold(release, holdNo); }
        catch (BizException unproven) {
            if (unproven.errorCode() != ErrorCode.CONFLICT && unproven.errorCode() != ErrorCode.NOT_FOUND) {
                throw unproven;
            }
            return walkInFailure(observed, "RESOURCE_RELEASE_REQUIRED");
        } catch (ServiceCallException unavailable) {
            boolean uncertain = unavailable.outcome() == CallOutcome.TIMEOUT
                    || unavailable.outcome() == CallOutcome.UNREACHABLE
                    || unavailable.outcome() == CallOutcome.REMOTE_ERROR
                        && unavailable.statusCode() == 0;
            if (!uncertain) { throw unavailable; }
            return walkInFailure(observed, "RESOURCE_RELEASE_REQUIRED");
        } catch (IllegalStateException malformedEvidence) {
            return walkInFailure(observed, "RESOURCE_RELEASE_REQUIRED");
        }
        return db.tx(() -> {
            var root = db.lock(scoped(observed.getEntityNo(), observed.getStoreNo(), observed.getAppointmentNo()));
            var fence = db.fences.selectScopedForUpdate(root.getEntityNo(), root.getStoreNo(), root.getAppointmentNo());
            var current = db.currents.selectScopedForUpdate(root.getEntityNo(), root.getStoreNo(),
                    root.getAppointmentNo());
            var op = operations.selectScopedForUpdate(root.getEntityNo(), root.getStoreNo(), observed.getOperationNo());
            AppointmentPersistence.require(sameWalkIn(observed, op));
            if (succeeded(op) || "REJECTED".equals(op.getPhase())) { return walkInView(op); }
            var original = appointmentPreparation.linkedReception(op);
            var latest = db.operation(release.getOperationNo());
            appointments.verifiedReceipt(original, true); appointments.verifiedReceipt(latest, true);
            AppointmentPersistence.require("RELEASE_REQUIRED".equals(original.getState())
                    && "CANCELLED".equals(root.getStatus()) && holdNo.equals(root.getHoldNo())
                    && "SUCCEEDED".equals(latest.getState())
                    && release.getAcceptPayload().equals(latest.getAcceptPayload())
                    && release.getReceipt().equals(latest.getReceipt())
                    && current.getOperationNo() == null && fence.getActiveIntentNo() == null
                    && db.roots.countWorkOrders(root.getEntityNo(), root.getStoreNo(), root.getAppointmentNo()) == 0);
            op.setPhase("REJECTED"); op.setFailureCode("RESOURCE_REJECTED");
            op.setResult(db.json.writeValueAsString(walkInView(op)));
            AppointmentPersistence.require(operations.updateById(op) == 1); clearRetry(op);
            action(root, op, "WALK_IN_RESOURCE_REJECTED", null, "{\"state\":\"REJECTED\"}", root.getVersion());
            return walkInView(op);
        });
    }
    private WalkInVO rejectWalkIn(BtyReceptionOperation observed) {
        return db.tx(() -> {
            var root = db.lock(scoped(observed.getEntityNo(), observed.getStoreNo(), observed.getAppointmentNo()));
            var fence = db.fences.selectScopedForUpdate(root.getEntityNo(), root.getStoreNo(), root.getAppointmentNo());
            var current = db.currents.selectScopedForUpdate(root.getEntityNo(), root.getStoreNo(),
                    root.getAppointmentNo());
            var op = operations.selectScopedForUpdate(root.getEntityNo(), root.getStoreNo(), observed.getOperationNo());
            AppointmentPersistence.require(sameWalkIn(observed, op));
            if (succeeded(op) || "REJECTED".equals(op.getPhase())) { return walkInView(op); }
            var linked = appointmentPreparation.linkedReception(op);
            AppointmentPersistence.require("REJECTED".equals(linked.getState()) && root.getHoldNo() == null
                    && fence.getActiveIntentNo() == null && current.getOperationNo() == null);
            op.setPhase("REJECTED"); op.setFailureCode("RESOURCE_REJECTED");
            op.setResult(db.json.writeValueAsString(walkInView(op)));
            AppointmentPersistence.require(operations.updateById(op) == 1); clearRetry(op);
            return walkInView(op);
        });
    }
    private boolean future(WalkInPayload payload) {
        return payload.matched() != null && ResourcePlanContract.decode(payload.matched().plan()).demands().stream()
                .flatMap(d -> d.intervals().stream()).allMatch(i -> i.startAt().isAfter(clock.instant()));
    }
    private WalkInVO walkInFailure(BtyReceptionOperation observed, String code) {
        return db.tx(() -> {
            var op = operations.selectScopedForUpdate(observed.getEntityNo(), observed.getStoreNo(),
                    observed.getOperationNo());
            AppointmentPersistence.require(sameWalkIn(observed, op));
            if (succeeded(op) || "REJECTED".equals(op.getPhase())) { return walkInView(op); }
            op.setFailureCode(code); AppointmentPersistence.require(operations.updateById(op) == 1); clearRetry(op);
            return walkInView(op);
        });
    }
    private void clearRetry(BtyReceptionOperation op) {
        operations.update(null, Wrappers.<BtyReceptionOperation>lambdaUpdate()
                .eq(BtyReceptionOperation::getId, op.getId()).set(BtyReceptionOperation::getNextRetryAt, null)
                .set(BtyReceptionOperation::getLeaseUntil, null));
    }
    private boolean sameWalkIn(BtyReceptionOperation expected, BtyReceptionOperation actual) {
        return actual != null && expected.getTenantNo().equals(actual.getTenantNo())
                && expected.getEntityNo().equals(actual.getEntityNo())
                && expected.getStoreNo().equals(actual.getStoreNo())
                && expected.getOperationNo().equals(actual.getOperationNo())
                && expected.getActorNo().equals(actual.getActorNo())
                && expected.getActorRealm().equals(actual.getActorRealm())
                && expected.getActorKind().equals(actual.getActorKind())
                && expected.getKeyHash().equals(actual.getKeyHash())
                && expected.getRequestHash().equals(actual.getRequestHash())
                && "RECEIVE_WALK_IN".equals(actual.getAction()) && "BAPI-048".equals(actual.getEndpoint());
    }
    private BtyReceptionOperation walkInOperation(String entity, String store, String number) {
        var op = db.read(() -> operations.selectScoped(entity, store, number));
        if (op == null) { throw BizException.of(ErrorCode.NOT_FOUND); }
        AppointmentPersistence.require("RECEIVE_WALK_IN".equals(op.getAction()) && "BAPI-048".equals(op.getEndpoint()));
        return op;
    }
    private WalkInPayload walkInPayload(BtyReceptionOperation op) {
        return db.json.readValue(op.getFrozenPayload(), WalkInPayload.class);
    }
    private WalkInVO walkInView(BtyReceptionOperation op) {
        if (succeeded(op)) { return db.json.readValue(op.getResult(), WalkInVO.class); }
        String recovery = "REJECTED".equals(op.getPhase()) ? null
                : "RESOURCE_RELEASE_REQUIRED".equals(op.getFailureCode()) ? "AUTHENTICATED_CANCEL"
                : "ARRIVAL_REQUIRES_RESCHEDULE".equals(op.getFailureCode()) ? "REQUEST_RESCHEDULE"
                : "AUTHENTICATED_OPERATION_RECOVERY";
        return new WalkInVO(op.getOperationNo(), "REJECTED".equals(op.getPhase()) ? "REJECTED" : "PENDING",
                op.getAppointmentNo(), null, walkInPayload(op).selectedStartAt(), recovery);
    }
    public ArrivalVO checkIn(String entity, String store, String actor, CheckInReq req, String key) {
        access.merchant(entity, store, actor, true); receptionAccess.customer(entity, store, actor);
        if (req == null) { throw BizException.of(ErrorCode.BAD_REQUEST); }
        var root = scoped(entity, store, req.appointmentNo());
        String hash = fingerprint("BAPI-047", entity, store, req);
        var prior = preparations.find(entity, store, actor, "BAPI-047", key, hash);
        if (succeeded(prior)) { return arrival(prior); }
        var proof = req.identityProof();
        VerifiedIdentity identity;
        if (proof.customerRef() != null) {
            String user = identities.customer(entity, store, proof.customerRef());
            AppointmentPersistence.require(ownership.owns(root, user));
            identity = new VerifiedIdentity("USER", user);
        } else {
            AppointmentPersistence.require(proof.guestNo().equals(root.getGuestRef()));
            identity = identities.verifyGuest(entity, store, proof.guestNo(), proof.claimToken());
            AppointmentPersistence.require("GUEST".equals(identity.kind()) || ownership.owns(root,
                    identity.reference()));
        }
        return arrive(root, actor, req.expectedRevision(), "BAPI-047", key, hash, identity, null);
    }
    public MyArrivalVO checkInMine(String user, MyCheckInReq req, String key) {
        access.consumer(user);
        if (req == null) { throw BizException.of(ErrorCode.BAD_REQUEST); }
        var root = db.root(req.appointmentNo()); access.root(root, true);
        String hash = fingerprint("CAPI-154", root.getEntityNo(), root.getStoreNo(), req);
        var prior = preparations.find(root.getEntityNo(), root.getStoreNo(), user, "CAPI-154", key, hash);
        ArrivalVO result;
        if (succeeded(prior)) { result = arrival(prior); }
        else {
            String canonical = identities.consumer(req.checkInProof().memberCode());
            AppointmentPersistence.require(ownership.owns(root, canonical));
            challenge(root, req.checkInProof().arrivalChallenge(), now());
            result = arrive(root, user, req.expectedRevision(), "CAPI-154", key, hash,
                    new VerifiedIdentity("USER", canonical), req.checkInProof().arrivalChallenge());
        }
        return new MyArrivalVO(result.appointmentNo(), result.revision(), result.status(), result.checkedInAt(),
                result.timing(), result.plannedStartAt(), result.plannedEndAt(), result.operationNo(),
                result.operationState());
    }
    private ArrivalVO arrive(BtyAppointment observed, String actor, long revision, String endpoint, String key,
            String hash, VerifiedIdentity identity, String challenge) {
        AppointmentPersistence.require("CONFIRMED".equals(observed.getStatus()));
        var evidence = appointments.currentArrangementEvidence(observed);
        return db.tx(() -> {
            var root = db.lock(observed);
            var fence = db.fences.selectScopedForUpdate(root.getEntityNo(), root.getStoreNo(), root.getAppointmentNo());
            AppointmentPersistence.require(fence != null && fence.getActiveIntentNo() == null);
            var current = db.current(root);
            var prior = preparations.find(root.getEntityNo(), root.getStoreNo(), actor, endpoint, key, hash);
            if (succeeded(prior)) { return arrival(prior); }
            AppointmentPersistence.require(root.getVersion() == revision && "CONFIRMED".equals(root.getStatus())
                    && root.getCheckedInAt() == null && current != null && current.getOperationNo() == null
                    && current.getArrangementRevision() == evidence.arrangementRevision()
                    && Objects.equals(observed.getHoldNo(), root.getHoldNo())
                    && Objects.equals(observed.getStartAt(), root.getStartAt())
                    && Objects.equals(observed.getEndAt(), root.getEndAt())
                    && Objects.equals(observed.getCustomerRef(), root.getCustomerRef())
                    && Objects.equals(observed.getGuestRef(), root.getGuestRef())
                    && db.roots.countWorkOrders(root.getEntityNo(), root.getStoreNo(), root.getAppointmentNo()) == 0);
            verifyArrangement(root, evidence);
            var at = now();
            if (!at.isBefore(root.getEndAt())) { throw BizException.of(ErrorCode.BEAUTY_ARRIVAL_REQUIRES_RESCHEDULE); }
            if (challenge != null) { challenge(root, challenge, at); }
            var op = preparations.begin(root.getEntityNo(), root.getStoreNo(), actor, endpoint, key, hash, identity,
                    new ReceptionPreparation.ArrivalPayload(1, root.getAppointmentNo(), revision));
            AppointmentPersistence.require(db.roots.markArrived(root.getEntityNo(), root.getStoreNo(),
                    root.getAppointmentNo(), revision, "CONFIRMED", at, actor, at) == 1);
            String timing = at.isBefore(root.getStartAt()) ? "EARLY" : at.equals(root
                    .getStartAt()) ? "ON_TIME" : "LATE";
            var result = new ArrivalVO(root.getAppointmentNo(), revision + 1, "CHECKED_IN", at.atOffset(ZoneOffset.UTC),
                    timing, root.getStartAt().atOffset(ZoneOffset.UTC), root.getEndAt().atOffset(ZoneOffset.UTC),
                    op.getOperationNo(), "SUCCEEDED");
            complete(op, result);
            action(root, op, "CHECKED_IN", null, "{\"state\":\"CHECKED_IN\"}", revision + 1);
            publish(root, op, revision + 1, "CHECKED_IN");
            return result;
        });
    }
    public ReceptionExceptionVO coordinateException(String entity, String store, String actor,
            ReceptionExceptionReq req, String key) {
        access.merchant(entity, store, actor, true);
        if (req == null) { throw BizException.of(ErrorCode.BAD_REQUEST); }
        var observed = scoped(entity, store, req.appointmentNo());
        String hash = fingerprint("BAPI-053", entity, store, req);
        var existing = preparations.find(entity, store, actor, "BAPI-053", key, hash);
        if (succeeded(existing)) { return db.json.readValue(existing.getResult(), ReceptionExceptionVO.class); }
        return db.tx(() -> {
            var root = db.lock(observed);
            var fence = db.fences.selectScopedForUpdate(entity, store, root.getAppointmentNo());
            AppointmentPersistence.require(fence != null);
            var prior = preparations.find(entity, store, actor, "BAPI-053", key, hash);
            if (succeeded(prior)) { return db.json.readValue(prior.getResult(), ReceptionExceptionVO.class); }
            AppointmentPersistence.require(root.getVersion().equals(req.expectedRevision()));
            var op = preparations.begin(entity, store, actor, "BAPI-053", key, hash, null,
                    new ReceptionPreparation.ExceptionPayload(1, root.getAppointmentNo(), req.kind(), req.proposal(),
                            req.expectedRevision()));
            var affected = db.roots.affectedTaskNos(entity, store, root.getAppointmentNo());
            String actionNo = action(root, op, "RECEPTION_EXCEPTION_REQUESTED", req.reason(),
                    db.json.writeValueAsString(new ExceptionSnapshot(req.kind(), req.proposal(), affected)),
                    root.getVersion());
            var result = new ReceptionExceptionVO(actionNo, root.getAppointmentNo(), "PENDING_REVIEW", affected,
                    op.getOperationNo());
            complete(op, result); publish(root, op, root.getVersion(), "PENDING_REVIEW");
            return result;
        });
    }
    /** The shared helper keeps later walk-in orchestration independent from order opening. */
    public BtyAppointmentOperation reassign(String entity, String store, String actor, String appointment,
            long revision, List<AppointmentAssignmentReq> assignments, OffsetDateTime startAt, String key) {
        return reassignment.reassign(entity, store, actor, appointment, revision, assignments, startAt, key);
    }
    private void verifyArrangement(BtyAppointment root, HoldEvidence evidence) {
        var row = db.arrangements.selectOne(Wrappers.<BtyAppointmentArrangement>lambdaQuery()
                .eq(BtyAppointmentArrangement::getTenantNo, root.getTenantNo())
                .eq(BtyAppointmentArrangement::getEntityNo, root.getEntityNo())
                .eq(BtyAppointmentArrangement::getAppointmentNo, root.getAppointmentNo())
                .eq(BtyAppointmentArrangement::getArrangementRevision, evidence.arrangementRevision()));
        AppointmentPersistence.require(row != null && root.getHoldNo().equals(evidence.hold().holdNo())
                && evidence.arrangementHash().equals(IdempotencyScope.requestHash(row.getPreviewNo(),
                        row.getHoldNo(), row.getSnapshot())));
    }
    private void challenge(BtyAppointment root, String code, LocalDateTime at) {
        AppointmentPersistence.require(db.read(() -> challenges.selectCount(Wrappers.<BtyArrivalChallenge>lambdaQuery()
                .eq(BtyArrivalChallenge::getTenantNo, root.getTenantNo())
                .eq(BtyArrivalChallenge::getEntityNo, root.getEntityNo())
                .eq(BtyArrivalChallenge::getStoreNo, root.getStoreNo()).eq(BtyArrivalChallenge::getCode, code)
                .le(BtyArrivalChallenge::getIssuedAt, at).gt(BtyArrivalChallenge::getExpiresAt, at))) == 1);
    }
    private BtyAppointment scoped(String entity, String store, String number) {
        var root = db.root(number);
        if (root == null || !entity.equals(root.getEntityNo()) || !store.equals(root.getStoreNo())) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return root;
    }
    private String fingerprint(String endpoint, String entity, String store, Object req) {
        var user = SecurityUtils.requireUser();
        return IdempotencyScope.requestHash("BEAUTY_RECEPTION_V1", endpoint, user.tenantNo(), entity, store,
                user.realm().name(), user.subjectKind().name(), user.userNo(), db.json.writeValueAsString(req));
    }
    private boolean succeeded(BtyReceptionOperation op) { return op != null && "SUCCEEDED".equals(op.getPhase()); }
    private ArrivalVO arrival(BtyReceptionOperation op) { return db.json.readValue(op.getResult(), ArrivalVO.class); }
    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
    }
    private void complete(BtyReceptionOperation op, Object result) {
        op.setPhase("SUCCEEDED"); op.setResult(db.json.writeValueAsString(result));
        AppointmentPersistence.require(operations.updateById(op) == 1);
    }
    private String action(BtyAppointment root, BtyReceptionOperation op, String action, String reason, String snapshot,
            long revision) {
        var row = new BtyActionLog(); row.setEntityNo(root.getEntityNo()); row.setStoreNo(root.getStoreNo());
        row.setActionNo(BizKey.next("BTAL")); row.setAggregateType("BEAUTY_APPOINTMENT");
        row.setAggregateNo(root.getAppointmentNo()); row.setAction(action); row.setRequestRef(op.getOperationNo());
        row.setOperatorRef(SecurityUtils.requireUser().userNo()); row.setExpectedRevision(root.getVersion());
        row.setResultRevision(revision); row.setReason(reason); row.setSafeSnapshot(snapshot); db.actions.insert(row);
        return row.getActionNo();
    }
    private void publish(BtyAppointment root, BtyReceptionOperation op, long revision, String state) {
        publisher.publish(new BeautyReceptionStateChanged(root.getAppointmentNo(), root.getEntityNo(),
                root.getStoreNo(), revision, state), new PublishOptions("BEAUTY:RECEPTION:" + op.getOperationNo(), 1));
    }
    private record ExceptionSnapshot(String kind, String proposal, List<String> affectedTaskNos) { }
}
