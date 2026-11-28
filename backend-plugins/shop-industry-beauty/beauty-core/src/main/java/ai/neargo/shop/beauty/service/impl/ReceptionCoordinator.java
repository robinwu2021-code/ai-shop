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
import ai.neargo.shop.event.AfterCommit;
import ai.neargo.shop.idem.IdempotencyScope;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/** Arrival observes owner proof outside locks and commits only local presence/audit facts under the root lock. */
public final class ReceptionCoordinator {
    private static final Logger LOG = LoggerFactory.getLogger(ReceptionCoordinator.class);
    private final AppointmentPersistence db;
    private final AppointmentAccess access;
    private final ReceptionAccess receptionAccess;
    private final ReceptionIdentity identities;
    private final CustomerOwnership ownership;
    private final ReceptionPreparation preparations;
    private final AppointmentCoordinator appointments;
    private final BeautyMappers.ReceptionOperationMapper operations;
    private final BeautyMappers.ArrivalChallengeMapper challenges;
    private final Clock clock;
    private final ReceptionReassignment reassignment;
    private final AppointmentMatching matching;
    private final AppointmentPreparation appointmentPreparation;
    private final WorkOrderCoordinator workOrders;
    public ReceptionCoordinator(AppointmentPersistence db, AppointmentAccess access, ReceptionAccess receptionAccess,
            ReceptionIdentity identities, CustomerOwnership ownership, ReceptionPreparation preparations,
            AppointmentCoordinator appointments, BeautyMappers.ReceptionOperationMapper operations,
            BeautyMappers.ArrivalChallengeMapper challenges, Clock clock,
            ReceptionReassignment reassignment, AppointmentMatching matching,
            AppointmentPreparation appointmentPreparation, WorkOrderCoordinator workOrders) {
        this.db = db; this.access = access; this.receptionAccess = receptionAccess; this.identities = identities;
        this.ownership = ownership; this.preparations = preparations; this.appointments = appointments;
        this.operations = operations; this.challenges = challenges; this.clock = clock;
        this.reassignment = reassignment; this.matching = matching;
        this.appointmentPreparation = appointmentPreparation; this.workOrders = workOrders;
        this.steps = new ReceptionSteps(db, operations);
    }
    /** 接待操作的唯一写入口（TDD §2.2.1）。 */
    private final ReceptionSteps steps;
    /** 散客接待还在进行中的阶段。 */
    private static final java.util.Set<String> ACTIVE = java.util.Set.of("RECEIVED",
            BtyReceptionOperation.RESOURCE_PENDING, BtyReceptionOperation.ARRIVAL_PENDING,
            BtyReceptionOperation.OPEN_PENDING);
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
                                    ReceptionPreparation.derived(entity, store, "BAPI-048", key)), null, null, null, null));
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
            if (BtyReceptionOperation.RESOURCE_PENDING.equals(op.getPhase())) {
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
            if (BtyReceptionOperation.RESOURCE_PENDING.equals(op.getPhase())) {
                var linked = appointmentPreparation.linkedReception(op);
                if ("PENDING".equals(linked.getState())) { linked = appointments.advance(linked.getOperationNo()); }
                if ("RELEASE_REQUIRED".equals(linked.getState())) {
                    return closeReleasedWalkIn(op);
                }
                if ("REJECTED".equals(linked.getState())) { return rejectWalkIn(op); }
                if (!"SUCCEEDED".equals(linked.getState())) { return walkInView(op); }
                final var active = op;
                op = steps.advance(active, java.util.Set.of(BtyReceptionOperation.RESOURCE_PENDING), () -> {
                    var root = db.lock(scoped(entity, store, active.getAppointmentNo()));
                    var fence = db.fences.selectScopedForUpdate(entity, store, root.getAppointmentNo());
                    var current = db.currents.selectScopedForUpdate(entity, store, root.getAppointmentNo());
                    var original = appointmentPreparation.linkedReception(active);
                    var receipt = appointments.verifiedReceipt(original, true);
                    AppointmentPersistence.require("SUCCEEDED".equals(receipt.status())
                            && "CONFIRM".equals(receipt.binding().action()) && "SUCCEEDED".equals(original.getState())
                            && "CONFIRMED".equals(root.getStatus()) && root.getHoldNo() != null
                            && fence.getActiveIntentNo() == null && current.getOperationNo() == null);
                    return root.getVersion();
                }, (locked, rootVersion) -> {
                    AppointmentPersistence.require(sameWalkIn(active, locked));
                    locked.setFrozenPayload(db.json.writeValueAsString(walkInPayload(locked)
                            .arrivalPending(rootVersion, now())));
                    locked.setPhase(BtyReceptionOperation.ARRIVAL_PENDING);
                    steps.save(locked); steps.clearRetry(locked);
                    return locked;
                }, locked -> locked);
            }
            if (BtyReceptionOperation.ARRIVAL_PENDING.equals(op.getPhase())) { op = arriveWalkIn(op, actor); }
            if (BtyReceptionOperation.OPEN_PENDING.equals(op.getPhase())) {
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
        var payload = walkInPayload(observed);
        AppointmentPersistence.require(payload.arrivalRevision() != null);
        long arrival = payload.arrivalRevision();
        AppointmentPersistence.require(payload.arriveAt() != null);
        var arriveAt = payload.arriveAt();
        boolean pending = "CONFIRMED".equals(original.getStatus());
        var evidence = pending ? appointments.currentArrangementEvidence(original) : null;
        return steps.advance(observed, java.util.Set.of(BtyReceptionOperation.ARRIVAL_PENDING), () -> {
            var root = db.lock(original);
            // 上次已标到店、提交后断了：只补接待操作
            if (arrived(root, arrival, arriveAt)) { return null; }
            var fence = db.fences.selectScopedForUpdate(root.getEntityNo(), root.getStoreNo(), root.getAppointmentNo());
            var current = db.currents.selectScopedForUpdate(root.getEntityNo(), root.getStoreNo(),
                    root.getAppointmentNo());
            AppointmentPersistence.require(evidence != null
                    && root.getVersion().equals(arrival) && "CONFIRMED".equals(root.getStatus())
                    && root.getCheckedInAt() == null && fence.getActiveIntentNo() == null
                    && current.getOperationNo() == null
                    && current.getArrangementRevision() == evidence.arrangementRevision()
                    && db.roots.countWorkOrders(root.getEntityNo(), root.getStoreNo(), root.getAppointmentNo()) == 0
                    && future(payload) && now().isBefore(root.getEndAt()));
            appointmentPreparation.linkedReception(observed);
            verifyArrangement(root, evidence);
            AppointmentPersistence.require(db.roots.markArrived(root.getEntityNo(), root.getStoreNo(),
                    root.getAppointmentNo(), root.getVersion(), "CONFIRMED", arriveAt, actor, now()) == 1);
            publish(root, observed, root.getVersion() + 1, "CHECKED_IN");
            return null;
        }, (op, ignored) -> {
            AppointmentPersistence.require(sameWalkIn(observed, op));
            op.setFrozenPayload(db.json.writeValueAsString(walkInPayload(op).withRevisions(arrival, arrival + 1)));
            op.setPhase(BtyReceptionOperation.OPEN_PENDING); steps.save(op);
            return op;
        }, op -> op);
    }
    /**
     * 预约已由这次接待标到店：已到店、版本正好加一、到店时刻就是这次受理时定的那个（TDD §2.6 第 12 项，
     * 不再靠操作日志认）。别的接待先标了到店的话，到店时刻对不上，这次不会冒领。
     */
    private boolean arrived(BtyAppointment root, long revision, LocalDateTime arriveAt) {
        return "CHECKED_IN".equals(root.getStatus()) && root.getVersion() == revision + 1
                && arriveAt != null && arriveAt.equals(root.getCheckedInAt());
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
        return steps.advance(observed, ACTIVE, () -> {
            var root = db.lock(scoped(observed.getEntityNo(), observed.getStoreNo(), observed.getAppointmentNo()));
            var fence = db.fences.selectScopedForUpdate(root.getEntityNo(), root.getStoreNo(), root.getAppointmentNo());
            var current = db.currents.selectScopedForUpdate(root.getEntityNo(), root.getStoreNo(),
                    root.getAppointmentNo());
            var original = appointmentPreparation.linkedReception(observed);
            var latest = db.operation(release.getOperationNo());
            appointments.verifiedReceipt(original, true); appointments.verifiedReceipt(latest, true);
            AppointmentPersistence.require("RELEASE_REQUIRED".equals(original.getState())
                    && "CANCELLED".equals(root.getStatus()) && holdNo.equals(root.getHoldNo())
                    && "SUCCEEDED".equals(latest.getState())
                    && release.getAcceptPayload().equals(latest.getAcceptPayload())
                    && release.getReceipt().equals(latest.getReceipt())
                    && current.getOperationNo() == null && fence.getActiveIntentNo() == null
                    && db.roots.countWorkOrders(root.getEntityNo(), root.getStoreNo(), root.getAppointmentNo()) == 0);
            return null;
        }, (op, ignored) -> {
            AppointmentPersistence.require(sameWalkIn(observed, op));
            op.setPhase("REJECTED"); op.setFailureCode("RESOURCE_REJECTED");
            op.setResult(db.json.writeValueAsString(walkInView(op)));
            steps.save(op); steps.clearRetry(op);
            return walkInView(op);
        }, this::walkInView);
    }
    private WalkInVO rejectWalkIn(BtyReceptionOperation observed) {
        return steps.advance(observed, ACTIVE, () -> {
            var root = db.lock(scoped(observed.getEntityNo(), observed.getStoreNo(), observed.getAppointmentNo()));
            var fence = db.fences.selectScopedForUpdate(root.getEntityNo(), root.getStoreNo(), root.getAppointmentNo());
            var current = db.currents.selectScopedForUpdate(root.getEntityNo(), root.getStoreNo(),
                    root.getAppointmentNo());
            var linked = appointmentPreparation.linkedReception(observed);
            AppointmentPersistence.require("REJECTED".equals(linked.getState()) && root.getHoldNo() == null
                    && fence.getActiveIntentNo() == null && current.getOperationNo() == null);
            return null;
        }, (op, ignored) -> {
            AppointmentPersistence.require(sameWalkIn(observed, op));
            op.setPhase("REJECTED"); op.setFailureCode("RESOURCE_REJECTED");
            op.setResult(db.json.writeValueAsString(walkInView(op)));
            steps.save(op); steps.clearRetry(op);
            return walkInView(op);
        }, this::walkInView);
    }
    private boolean future(WalkInPayload payload) {
        return payload.matched() != null && ResourcePlanContract.decode(payload.matched().plan()).demands().stream()
                .flatMap(d -> d.intervals().stream()).allMatch(i -> i.startAt().isAfter(clock.instant()));
    }
    private WalkInVO walkInFailure(BtyReceptionOperation observed, String code) {
        return steps.update(observed, op -> {
            AppointmentPersistence.require(sameWalkIn(observed, op));
            if (succeeded(op) || "REJECTED".equals(op.getPhase())) { return walkInView(op); }
            op.setFailureCode(code); steps.save(op); steps.clearRetry(op);
            return walkInView(op);
        });
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
        // 接待操作在行业库、预约在交易库：先受理，再在交易事务里标到店，最后落结果（ReceptionSteps.once）
        var prior = preparations.find(observed.getEntityNo(), observed.getStoreNo(), actor, endpoint, key, hash);
        if (succeeded(prior)) { return arrival(prior); }
        AppointmentPersistence.require(prior != null || "CONFIRMED".equals(observed.getStatus()));
        var evidence = "CONFIRMED".equals(observed.getStatus())
                ? appointments.currentArrangementEvidence(observed) : null;
        var op = preparations.begin(observed.getEntityNo(), observed.getStoreNo(), actor, endpoint, key, hash, identity,
                new ReceptionPreparation.ArrivalPayload(1, observed.getAppointmentNo(), revision, now()));
        // 到店时刻以受理时定的为准（重做沿用同一个）
        var at = db.json.readValue(op.getFrozenPayload(), ReceptionPreparation.ArrivalPayload.class).arriveAt();
        AppointmentPersistence.require(at != null);
        return steps.once(op, () -> {
            var root = db.lock(observed);
            // 上次已标到店、提交后断了：按预约上的到店时刻补结果
            if (arrived(root, revision, at)) { return arrivalResult(root, revision, root.getCheckedInAt(), op); }
            var fence = db.fences.selectScopedForUpdate(root.getEntityNo(), root.getStoreNo(), root.getAppointmentNo());
            AppointmentPersistence.require(fence != null && fence.getActiveIntentNo() == null);
            var current = db.current(root);
            AppointmentPersistence.require(evidence != null && root.getVersion() == revision && "CONFIRMED".equals(root.getStatus())
                    && root.getCheckedInAt() == null && current != null && current.getOperationNo() == null
                    && current.getArrangementRevision() == evidence.arrangementRevision()
                    && Objects.equals(observed.getHoldNo(), root.getHoldNo())
                    && Objects.equals(observed.getStartAt(), root.getStartAt())
                    && Objects.equals(observed.getEndAt(), root.getEndAt())
                    && Objects.equals(observed.getCustomerRef(), root.getCustomerRef())
                    && Objects.equals(observed.getGuestRef(), root.getGuestRef())
                    && db.roots.countWorkOrders(root.getEntityNo(), root.getStoreNo(), root.getAppointmentNo()) == 0);
            verifyArrangement(root, evidence);
            if (!at.isBefore(root.getEndAt())) { throw BizException.of(ErrorCode.BEAUTY_ARRIVAL_REQUIRES_RESCHEDULE); }
            if (challenge != null) { challenge(root, challenge, at); }
            AppointmentPersistence.require(db.roots.markArrived(root.getEntityNo(), root.getStoreNo(),
                    root.getAppointmentNo(), revision, "CONFIRMED", at, actor, at) == 1);
            publish(root, op, revision + 1, "CHECKED_IN");
            return arrivalResult(root, revision, at, op);
        }, (row, result) -> { complete(row, result); return result; }, this::arrivalOrConflict);
    }
    private ArrivalVO arrivalResult(BtyAppointment root, long revision, LocalDateTime at, BtyReceptionOperation op) {
        String timing = at.isBefore(root.getStartAt()) ? "EARLY" : at.equals(root.getStartAt()) ? "ON_TIME" : "LATE";
        return new ArrivalVO(root.getAppointmentNo(), revision + 1, "CHECKED_IN", at.atOffset(ZoneOffset.UTC),
                timing, root.getStartAt().atOffset(ZoneOffset.UTC), root.getEndAt().atOffset(ZoneOffset.UTC),
                op.getOperationNo(), "SUCCEEDED");
    }
    private ArrivalVO arrivalOrConflict(BtyReceptionOperation op) {
        if (succeeded(op)) { return arrival(op); }
        throw BizException.of(ErrorCode.CONFLICT);
    }
    public ReceptionExceptionVO coordinateException(String entity, String store, String actor,
            ReceptionExceptionReq req, String key) {
        access.merchant(entity, store, actor, true);
        if (req == null) { throw BizException.of(ErrorCode.BAD_REQUEST); }
        var observed = scoped(entity, store, req.appointmentNo());
        String hash = fingerprint("BAPI-053", entity, store, req);
        var existing = preparations.find(entity, store, actor, "BAPI-053", key, hash);
        if (succeeded(existing)) { return db.json.readValue(existing.getResult(), ReceptionExceptionVO.class); }
        // 先受理（行业库），再在交易事务里记异常日志，最后落结果（ReceptionSteps.once）
        var op = preparations.begin(entity, store, actor, "BAPI-053", key, hash, null,
                new ReceptionPreparation.ExceptionPayload(1, observed.getAppointmentNo(), req.kind(), req.proposal(),
                        req.expectedRevision(), req.reason()));
        // 受影响的任务在行业库：在交易事务外读
        var affected = workOrders.affectedTaskNos(entity, store, observed.getAppointmentNo());
        return steps.once(op, () -> {
            var root = db.lock(observed);
            var fence = db.fences.selectScopedForUpdate(entity, store, root.getAppointmentNo());
            AppointmentPersistence.require(fence != null);
            AppointmentPersistence.require(root.getVersion().equals(req.expectedRevision()));
            // 申请只核对、不改预约：申请号就是接待操作号，内容随结果记在接待操作行上（TDD §2.6 第 12 项）
            publish(root, op, root.getVersion(), "PENDING_REVIEW");
            return new ReceptionExceptionVO(op.getOperationNo(), root.getAppointmentNo(), "PENDING_REVIEW", affected,
                    op.getOperationNo());
        }, (row, result) -> { complete(row, result); return result; }, row -> {
            if (succeeded(row)) { return db.json.readValue(row.getResult(), ReceptionExceptionVO.class); }
            throw BizException.of(ErrorCode.CONFLICT);
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
    /** 只在 ReceptionSteps 的回调里调用（接待操作已锁住）。 */
    private void complete(BtyReceptionOperation op, Object result) {
        op.setPhase("SUCCEEDED"); op.setResult(db.json.writeValueAsString(result));
        steps.save(op);
    }
    private void publish(BtyAppointment root, BtyReceptionOperation op, long revision, String state) {
        String requestId = MDC.get("requestId");
        String operationNo = op.getOperationNo();
        String appointmentNo = root.getAppointmentNo();
        AfterCommit.run("beauty.reception.state_committed", () -> LOG.info(
                "beauty.reception.state_committed requestId={} operationNo={} appointmentNo={} state={}",
                requestId, operationNo, appointmentNo, state));
    }
}
