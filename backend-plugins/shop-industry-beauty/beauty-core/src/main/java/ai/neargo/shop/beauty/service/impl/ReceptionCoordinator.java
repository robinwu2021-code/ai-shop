package ai.neargo.shop.beauty.service.impl;

import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.beauty.dto.AppointmentAssignmentReq;
import ai.neargo.shop.beauty.dto.ArrivalVO;
import ai.neargo.shop.beauty.dto.CheckInReq;
import ai.neargo.shop.beauty.dto.MyArrivalVO;
import ai.neargo.shop.beauty.dto.MyCheckInReq;
import ai.neargo.shop.beauty.dto.ReceptionExceptionReq;
import ai.neargo.shop.beauty.dto.ReceptionExceptionVO;
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
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.event.outbox.PublishOptions;
import ai.neargo.shop.idem.IdempotencyScope;
import ai.neargo.shop.spi.beauty.BeautyReceptionStateChanged;
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
    public ReceptionCoordinator(AppointmentPersistence db, AppointmentAccess access, ReceptionAccess receptionAccess,
            ReceptionIdentity identities, CustomerOwnership ownership, ReceptionPreparation preparations,
            AppointmentCoordinator appointments, BeautyMappers.ReceptionOperationMapper operations,
            BeautyMappers.ArrivalChallengeMapper challenges, OutboxPublisher publisher, Clock clock,
            ReceptionReassignment reassignment) {
        this.db = db; this.access = access; this.receptionAccess = receptionAccess; this.identities = identities;
        this.ownership = ownership; this.preparations = preparations; this.appointments = appointments;
        this.operations = operations; this.challenges = challenges; this.publisher = publisher; this.clock = clock;
        this.reassignment = reassignment;
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
