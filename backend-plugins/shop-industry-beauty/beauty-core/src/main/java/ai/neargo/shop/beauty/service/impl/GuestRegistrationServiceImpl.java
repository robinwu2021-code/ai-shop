package ai.neargo.shop.beauty.service.impl;

import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.beauty.dto.ClaimGuestReq;
import ai.neargo.shop.beauty.dto.GuestClaimVO;
import ai.neargo.shop.beauty.dto.GuestRegistrationVO;
import ai.neargo.shop.beauty.dto.RegisterGuestReq;
import ai.neargo.shop.beauty.entity.BtyActionLog;
import ai.neargo.shop.beauty.entity.BtyGuestClaimReceipt;
import ai.neargo.shop.beauty.entity.BtyReceptionOperation;
import ai.neargo.shop.beauty.mapper.BeautyMappers;
import ai.neargo.shop.beauty.policy.ReceptionAccess;
import ai.neargo.shop.beauty.policy.ReceptionIdentity;
import ai.neargo.shop.beauty.service.GuestRegistrationService;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.event.outbox.PublishOptions;
import ai.neargo.shop.idem.IdempotencyScope;
import ai.neargo.shop.spi.beauty.BeautyReceptionStateChanged;
import ai.neargo.shop.spi.member.GuestRegistrationPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;

/** Registration idempotency stays with the owner; verified claims gain a local immutable authorization receipt. */
public final class GuestRegistrationServiceImpl implements GuestRegistrationService {
    private final AppointmentPersistence db;
    private final ReceptionAccess access;
    private final ReceptionIdentity identities;
    private final ReceptionPreparation preparations;
    private final ObjectProvider<GuestRegistrationPort> owner;
    private final BeautyMappers.ReceptionOperationMapper operations;
    private final BeautyMappers.GuestClaimReceiptMapper receipts;
    private final OutboxPublisher publisher;
    public GuestRegistrationServiceImpl(AppointmentPersistence db, ReceptionAccess access, ReceptionIdentity identities,
            ReceptionPreparation preparations, ObjectProvider<GuestRegistrationPort> owner,
            BeautyMappers.ReceptionOperationMapper operations, BeautyMappers.GuestClaimReceiptMapper receipts,
            OutboxPublisher publisher) {
        this.db = db; this.access = access; this.identities = identities; this.preparations = preparations;
        this.owner = owner; this.operations = operations; this.receipts = receipts; this.publisher = publisher;
    }
    @Override
    public GuestRegistrationVO registerGuest(String entity, String store, String actor, RegisterGuestReq req,
            String key) {
        var scope = access.customer(entity, store, actor);
        if (req == null) { throw BizException.of(ErrorCode.BAD_REQUEST); }
        String derived = ReceptionPreparation.derived(entity, store, "BAPI-031", key);
        try {
            var result = owner.getObject().register(scope, new GuestRegistrationPort.RegisterCommand(req.contact(),
                    req.displayName(), req.consent(), derived));
            if (result == null || result.guestNo() == null || result.guestNo().isBlank()
                    || !"REGISTERED".equals(result.state()) || result.revision() != 1 || result.claimToken() == null
                    || !result.claimToken().matches("[A-Za-z0-9_-]{43}")) {
                throw new IllegalStateException("Invalid guest registration result");
            }
            return new GuestRegistrationVO(null, result.guestNo(), result.claimToken(), result.state(),
                    result.revision(), "SUCCEEDED", null);
        } catch (GuestRegistrationPort.ResultUnknown unknown) {
            return new GuestRegistrationVO(null, null, null, null, null, "PENDING", "REQUIRES_ORIGINAL_ACTOR");
        }
    }
    @Override
    public GuestClaimVO claimGuest(String entity, String store, String actor, String guest, ClaimGuestReq req,
            String key) {
        var scope = access.customer(entity, store, actor);
        if (guest == null || guest.isBlank() || guest.length() > 32 || req == null) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        String hash = IdempotencyScope.requestHash("CLAIM_V1", guest, req.verifiedIdentity(), req.claimToken());
        var prior = preparations.findClaim(entity, store, key, hash);
        if (prior != null) {
            if ("SUCCEEDED".equals(prior.getPhase())) { return result(prior); }
            var fact = owner.getObject().get(scope, guest); ReceptionIdentity.validFact(fact, guest);
            if ("CLAIMED".equals(fact.state())) { return finish(prior, fact); }
        }
        String user = identities.customer(entity, store, req.verifiedIdentity());
        var verified = owner.getObject().verify(scope, guest, req.claimToken());
        ReceptionIdentity.validFact(verified, guest);
        if ("CLAIMED".equals(verified.state())) { AppointmentPersistence.require(user.equals(verified.userNo())); }
        var op = preparations.beginClaim(entity, store, guest, user, key, hash);
        AppointmentPersistence.require(user.equals(op.getIdentityRef()));
        if ("SUCCEEDED".equals(op.getPhase())) { return result(op); }
        if ("CLAIMED".equals(verified.state())) { return finish(op, verified); }
        try {
            var claimed = owner.getObject().claim(scope, guest, new GuestRegistrationPort.ClaimCommand(
                    req.verifiedIdentity(), req.claimToken(), op.getDerivedKeyHash()));
            if (claimed == null) { throw new IllegalStateException("Missing claim result"); }
            return finish(op, new GuestRegistrationPort.GuestFact(claimed.guestNo(), "CLAIMED", claimed.userNo(),
                    claimed.revision()));
        } catch (GuestRegistrationPort.ResultUnknown unknown) {
            return pending(op, "AUTHENTICATED_CLAIM_RECOVERY");
        }
    }
    /** Internal recovery consumes an original verified intent under the current authorized employee's identity. */
    public GuestClaimVO recoverClaim(String entity, String store, String actor, String operationNo) {
        var scope = access.customer(entity, store, actor);
        var op = db.read(() -> operations.selectOne(Wrappers.<BtyReceptionOperation>lambdaQuery()
                .eq(BtyReceptionOperation::getTenantNo, "MAIN").eq(BtyReceptionOperation::getEntityNo, entity)
                .eq(BtyReceptionOperation::getStoreNo, store).eq(BtyReceptionOperation::getOperationNo, operationNo)
                .eq(BtyReceptionOperation::getEndpoint, "BAPI-032")));
        if (op == null) { throw BizException.of(ErrorCode.NOT_FOUND); }
        if ("SUCCEEDED".equals(op.getPhase())) { return result(op); }
        var payload = payload(op);
        var fact = owner.getObject().get(scope, payload.guestNo());
        ReceptionIdentity.validFact(fact, payload.guestNo());
        return "CLAIMED".equals(fact.state()) ? finish(op, fact) : pending(op, "REQUIRES_FRESH_IDENTITY_PROOF");
    }
    private GuestClaimVO finish(BtyReceptionOperation observed, GuestRegistrationPort.GuestFact fact) {
        var original = payload(observed);
        ReceptionIdentity.validFact(fact, original.guestNo());
        AppointmentPersistence.require("CLAIMED".equals(fact.state())
                && original.targetUserNo().equals(fact.userNo()) && observed.getIdentityRef().equals(fact.userNo()));
        try { return finishTransaction(observed, fact); }
        catch (DuplicateKeyException race) { return finishTransaction(observed, fact); }
    }
    private GuestClaimVO finishTransaction(BtyReceptionOperation observed, GuestRegistrationPort.GuestFact fact) {
        return db.tx(() -> {
            var op = operations.selectScopedForUpdate(observed.getEntityNo(), observed.getStoreNo(),
                    observed.getOperationNo());
            AppointmentPersistence.require(op != null && op.getRequestHash().equals(observed.getRequestHash()));
            if ("SUCCEEDED".equals(op.getPhase())) { return result(op); }
            String hash = IdempotencyScope.requestHash("GUEST_CLAIM_RECEIPT_V1", op.getTenantNo(), op.getEntityNo(),
                    op.getStoreNo(), fact.guestNo(), "CLAIMED", fact.userNo(), Long.toString(fact.revision()));
            var receipt = receipts.selectOne(Wrappers.<BtyGuestClaimReceipt>lambdaQuery()
                    .eq(BtyGuestClaimReceipt::getTenantNo, op.getTenantNo())
                    .eq(BtyGuestClaimReceipt::getEntityNo, op.getEntityNo())
                    .eq(BtyGuestClaimReceipt::getStoreNo, op.getStoreNo())
                    .eq(BtyGuestClaimReceipt::getGuestNo, fact.guestNo()));
            if (receipt == null) {
                receipt = new BtyGuestClaimReceipt(); receipt.setEntityNo(op.getEntityNo());
                receipt.setStoreNo(op.getStoreNo()); receipt.setGuestNo(fact.guestNo());
                receipt.setUserNo(fact.userNo()); receipt.setClaimRevision(fact.revision());
                receipt.setOwnerReceiptHash(hash); receipt.setRecordedAt(AppointmentPersistence.now());
                receipts.insert(receipt);
            } else {
                AppointmentPersistence.require(fact.userNo().equals(receipt.getUserNo())
                        && fact.revision() == receipt.getClaimRevision() && hash.equals(receipt.getOwnerReceiptHash()));
            }
            var result = new GuestClaimVO(op.getOperationNo(), fact.guestNo(), fact.userNo(), fact.revision(),
                    "SUCCEEDED", null);
            op.setPhase("SUCCEEDED"); op.setResult(db.json.writeValueAsString(result));
            AppointmentPersistence.require(operations.updateById(op) == 1);
            var log = new BtyActionLog(); log.setEntityNo(op.getEntityNo()); log.setStoreNo(op.getStoreNo());
            log.setActionNo(BizKey.next("BTAL")); log.setAggregateType("BEAUTY_GUEST_CLAIM");
            log.setAggregateNo(fact.guestNo()); log.setAction("GUEST_CLAIM_RECEIPT_RECORDED");
            log.setOperatorRef(SecurityUtils.requireUser().userNo());
            log.setExpectedRevision(0L); log.setResultRevision(fact.revision());
            log.setSafeSnapshot("{\"state\":\"CLAIMED\"}"); log.setRequestRef(op.getOperationNo());
            db.actions.insert(log);
            publisher.publish(new BeautyReceptionStateChanged(fact.guestNo(), op.getEntityNo(), op.getStoreNo(),
                    fact.revision(), "CLAIMED"), new PublishOptions("BEAUTY:RECEPTION:" + op.getOperationNo(), 1));
            return result;
        });
    }
    private ReceptionPreparation.ClaimPayload payload(BtyReceptionOperation op) {
        return db.json.readValue(op.getFrozenPayload(), ReceptionPreparation.ClaimPayload.class);
    }
    private GuestClaimVO result(BtyReceptionOperation op) {
        return db.json.readValue(op.getResult(), GuestClaimVO.class);
    }
    private GuestClaimVO pending(BtyReceptionOperation op, String action) {
        return new GuestClaimVO(op.getOperationNo(), payload(op).guestNo(), op.getIdentityRef(), null,
                "PENDING", action);
    }
}
