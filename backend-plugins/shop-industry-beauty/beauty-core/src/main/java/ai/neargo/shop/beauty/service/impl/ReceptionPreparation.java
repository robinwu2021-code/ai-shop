package ai.neargo.shop.beauty.service.impl;

import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.beauty.dto.AppointmentAssignmentReq;
import ai.neargo.shop.beauty.dto.AppointmentItemReq;
import ai.neargo.shop.beauty.entity.BtyReceptionOperation;
import ai.neargo.shop.beauty.mapper.BeautyMappers;
import ai.neargo.shop.beauty.policy.ReceptionIdentity.VerifiedIdentity;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.idem.IdempotencyScope;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Durable verified claim intents contain only canonical references, never tickets or contact fingerprints. */
public final class ReceptionPreparation {
    private final AppointmentPersistence db;
    private final BeautyMappers.ReceptionOperationMapper operations;
    public ReceptionPreparation(AppointmentPersistence db, BeautyMappers.ReceptionOperationMapper operations) {
        this.db = db; this.operations = operations;
    }
    public BtyReceptionOperation findClaim(String entity, String store, String key, String hash) {
        var user = SecurityUtils.requireUser();
        var prior = db.read(() -> operations.selectOne(Wrappers.<BtyReceptionOperation>lambdaQuery()
                .eq(BtyReceptionOperation::getTenantNo, user.tenantNo()).eq(BtyReceptionOperation::getEntityNo, entity)
                .eq(BtyReceptionOperation::getStoreNo, store).eq(BtyReceptionOperation::getActorNo, user.userNo())
                .eq(BtyReceptionOperation::getActorRealm, user.realm().name())
                .eq(BtyReceptionOperation::getActorKind, user.subjectKind().name())
                .eq(BtyReceptionOperation::getEndpoint, "BAPI-032")
                .eq(BtyReceptionOperation::getKeyHash,
                        IdempotencyScope.requestHash(IdempotencyScope.requireKey(key)))));
        if (prior != null) { AppointmentPersistence.require(hash.equals(prior.getRequestHash())); }
        return prior;
    }
    public BtyReceptionOperation beginClaim(String entity, String store, String guest, String target,
            String key, String hash) {
        var prior = findClaim(entity, store, key, hash);
        if (prior != null) { return prior; }
        var user = SecurityUtils.requireUser();
        var op = new BtyReceptionOperation();
        op.setEntityNo(entity); op.setStoreNo(store); op.setOperationNo(BizKey.next("BTRO"));
        op.setActorNo(user.userNo()); op.setActorRealm(user.realm().name()); op.setActorKind(user.subjectKind().name());
        op.setEndpoint("BAPI-032"); op.setAction("CLAIM_GUEST");
        op.setKeyHash(IdempotencyScope.requestHash(IdempotencyScope.requireKey(key))); op.setRequestHash(hash);
        op.setIdentityKind("USER"); op.setIdentityRef(target); op.setPhase("RECEIVED");
        op.setDerivedKeyHash(derived(entity, store, "BAPI-032", key));
        op.setFrozenPayload(db.json.writeValueAsString(new ClaimPayload(1, guest, target)));
        try { db.tx(() -> { operations.insert(op); return null; }); }
        catch (DuplicateKeyException raced) {
            var winner = findClaim(entity, store, key, hash);
            if (winner == null) { throw raced; }
            return winner;
        }
        return op;
    }
    public BtyReceptionOperation find(String entity, String store, String actor, String endpoint,
            String key, String hash) {
        var user = SecurityUtils.requireUser();
        AppointmentPersistence.require(actor.equals(user.userNo()));
        var op = db.read(() -> operations.selectOne(Wrappers.<BtyReceptionOperation>lambdaQuery()
                .eq(BtyReceptionOperation::getTenantNo, user.tenantNo()).eq(BtyReceptionOperation::getEntityNo, entity)
                .eq(BtyReceptionOperation::getStoreNo, store).eq(BtyReceptionOperation::getActorNo, actor)
                .eq(BtyReceptionOperation::getActorRealm, user.realm().name())
                .eq(BtyReceptionOperation::getActorKind, user.subjectKind().name())
                .eq(BtyReceptionOperation::getEndpoint, endpoint)
                .eq(BtyReceptionOperation::getKeyHash,
                        IdempotencyScope.requestHash(IdempotencyScope.requireKey(key)))));
        if (op != null) { AppointmentPersistence.require(hash.equals(op.getRequestHash())); }
        return op;
    }
    public BtyReceptionOperation begin(String entity, String store, String actor, String endpoint,
            String key, String hash, VerifiedIdentity identity, ReceptionPayload payload) {
        var existing = find(entity, store, actor, endpoint, key, hash);
        if (existing != null) { return existing; }
        var user = SecurityUtils.requireUser();
        var op = new BtyReceptionOperation(); op.setTenantNo(user.tenantNo());
        op.setEntityNo(entity); op.setStoreNo(store);
        op.setOperationNo(BizKey.next("BTRO")); op.setActorNo(actor); op.setActorRealm(user.realm().name());
        op.setActorKind(user.subjectKind().name()); op.setEndpoint(endpoint);
        op.setAction(payload instanceof WalkInPayload ? "RECEIVE_WALK_IN"
                : payload instanceof ArrivalPayload ? "CHECK_IN"
                : payload instanceof WorkOrderCoordinator.OpenPayload ? "OPEN_WORK_ORDER" : "RECEPTION_EXCEPTION");
        op.setKeyHash(IdempotencyScope.requestHash(IdempotencyScope.requireKey(key))); op.setRequestHash(hash);
        op.setAppointmentNo(payload instanceof WalkInPayload ? null : payload.appointmentNo());
        op.setPhase(payload instanceof WorkOrderCoordinator.OpenPayload ? "OPEN_PENDING" : "RECEIVED");
        if (identity != null) { op.setIdentityKind(identity.kind()); op.setIdentityRef(identity.reference()); }
        op.setDerivedKeyHash(derived(entity, store, endpoint, key));
        op.setFrozenPayload(db.json.writeValueAsString(payload));
        if (TransactionSynchronizationManager.isActualTransactionActive()) { operations.insert(op); }
        else {
            try { db.tx(() -> { operations.insert(op); return null; }); }
            catch (DuplicateKeyException raced) {
                var winner = find(entity, store, actor, endpoint, key, hash);
                if (winner == null) { throw raced; }
                return winner;
            }
        }
        return op;
    }
    public sealed interface ReceptionPayload permits ArrivalPayload, ExceptionPayload,
            WorkOrderCoordinator.OpenPayload, WalkInPayload {
        String appointmentNo();
    }
    /** Freeze once outside the root lock chain; a concurrent winner always keeps its original complete plan. */
    public BtyReceptionOperation freezeWalkIn(BtyReceptionOperation observed, AppointmentMatching.Matched match) {
        return db.tx(() -> {
            var row = operations.selectScopedForUpdate(observed.getEntityNo(), observed.getStoreNo(),
                    observed.getOperationNo());
            AppointmentPersistence.require(row != null && row.getRequestHash().equals(observed.getRequestHash())
                    && row.getDerivedKeyHash().equals(observed.getDerivedKeyHash()));
            var payload = db.json.readValue(row.getFrozenPayload(), WalkInPayload.class);
            if (!"RECEIVED".equals(row.getPhase()) || payload.matched() != null) { return row; }
            AppointmentPersistence.require(match.scope().entityNo().equals(row.getEntityNo())
                    && match.scope().storeNo().equals(row.getStoreNo()));
            row.setFrozenPayload(db.json.writeValueAsString(payload.withMatch(match, IdempotencyScope.requestHash(
                    "BEAUTY_WALK_IN_CREATE_V1", row.getOperationNo(), row.getRequestHash(), payload.previewNo(),
                    match.plan()))));
            AppointmentPersistence.require(operations.updateById(row) == 1);
            return row;
        });
    }
    public record WalkInPayload(int schemaVersion, String appointmentNo, String previewNo,
            String appointmentOperationNo, List<AppointmentItemReq> items,
            List<AppointmentAssignmentReq> assignments, OffsetDateTime requestedStartAt,
            OffsetDateTime selectedStartAt, AppointmentMatching.Matched matched,
            String appointmentKey, String appointmentRequestHash, Long arrivalRevision, Long openingRevision)
            implements ReceptionPayload {
        public WalkInPayload { items = List.copyOf(items); assignments = List.copyOf(assignments); }
        WalkInPayload withMatch(AppointmentMatching.Matched value, String hash) {
            return new WalkInPayload(schemaVersion, appointmentNo, previewNo, appointmentOperationNo, items,
                    assignments, requestedStartAt, value.startAt(), value, appointmentKey, hash, null, null);
        }
        WalkInPayload withRevisions(Long arrival, Long opening) {
            return new WalkInPayload(schemaVersion, appointmentNo, previewNo, appointmentOperationNo, items,
                    assignments, requestedStartAt, selectedStartAt, matched, appointmentKey,
                    appointmentRequestHash, arrival, opening);
        }
    }
    public record ArrivalPayload(int schemaVersion, String appointmentNo, long expectedRevision)
            implements ReceptionPayload { }
    public record ExceptionPayload(int schemaVersion, String appointmentNo, String kind, String proposal,
            long expectedRevision) implements ReceptionPayload { }
    public static String derived(String entity, String store, String endpoint, String key) {
        var user = SecurityUtils.requireUser();
        return IdempotencyScope.requestHash("BEAUTY_RECEPTION_V1", user.tenantNo(), entity, store,
                user.realm().name(), user.subjectKind().name(), user.userNo(), endpoint,
                IdempotencyScope.requestHash(IdempotencyScope.requireKey(key)));
    }
    public record ClaimPayload(int schemaVersion, String guestNo, String targetUserNo) { }
}
