package ai.neargo.shop.beauty.service.impl;

import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.beauty.dto.AppointmentPreviewVO;
import ai.neargo.shop.beauty.entity.BtyAppointment;
import ai.neargo.shop.beauty.entity.BtyActionLog;
import ai.neargo.shop.beauty.entity.BtyAppointmentCurrent;
import ai.neargo.shop.beauty.entity.BtyAppointmentOperation;
import ai.neargo.shop.beauty.entity.BtyAppointmentPreparation;
import ai.neargo.shop.beauty.entity.BtyAppointmentResourceFence;
import ai.neargo.shop.beauty.policy.AppointmentAccess;
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
import java.time.ZoneOffset;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;

/** Commits stable requests before acceptance so unknown network responses can never generate replacement commands. */
public final class AppointmentPreparationService {
    private final AppointmentPersistence db;
    private final AppointmentAccess access;
    private final ObjectProvider<CustomerIdentityPort> customers;
    private final ObjectProvider<ServiceCatalogFactsPort> products;
    public AppointmentPreparationService(AppointmentPersistence db, AppointmentAccess access,
            ObjectProvider<CustomerIdentityPort> customers, ObjectProvider<ServiceCatalogFactsPort> products) {
        this.db = db;
        this.access = access;
        this.customers = customers;
        this.products = products;
    }
    public AppointmentPreviewVO save(AppointmentMatching.Matched match, String customerRef, BtyAppointment target,
            long expected) {
        var user = SecurityUtils.requireUser();
        String customer;
        if (user.isConsumer()) { access.consumer(user.userNo()); customer = user.userNo(); }
        else if (target != null) { customer = target.getCustomerRef(); }
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
        p.setGuestRef(customer == null ? target == null ? BizKey.next("BTG") : target.getGuestRef() : null);
        p.setAppointmentNo(target == null ? null : target.getAppointmentNo()); p.setExpectedRevision(expected);
        p.setPolicyVersionNo(match.policyVersionNo()); p.setStartAt(match.startAt().toLocalDateTime());
        p.setEndAt(match.endAt().toLocalDateTime()); p.setTimezone("UTC");
        p.setFrozenPlan(match.plan()); p.setPlanHash(ResourcePlanContract.hash(match.plan()));
        p.setSnapshot(db.json.writeValueAsString(match.snapshot()));
        p.setExpiresAt(AppointmentPersistence.now().plusMinutes(5));
        db.tx(() -> { db.preparations.insert(p); return null; });
        return view(p);
    }
    public void customer(BtyAppointmentPreparation preview, String reference) {
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
                AppointmentPersistence.require("HELD".equals(root.getStatus()) || "CONFIRMED".equals(root.getStatus()));
                var current = db.current(root);
                AppointmentPersistence.require(current.getOperationNo() == null);
                String operationNo = BizKey.next("BTOP");
                var actor = access.actor();
                var op = new BtyAppointmentOperation();
                op.setEntityNo(root.getEntityNo()); op.setStoreNo(root.getStoreNo()); op.setOperationNo(operationNo);
                op.setActorNo(actor.operatorNo()); op.setActorRealm(actor.realm());
                        op.setActorKind(actor.subjectKind());
                op.setKeyHash(IdempotencyScope.requestHash(IdempotencyScope.requireKey(key))); op.setRequestHash(hash);
                op.setAppointmentNo(root.getAppointmentNo()); op.setPreviewNo(preview.getPreviewNo());
                op.setAction(action); op.setState("PENDING"); op.setNextRetryAt(AppointmentPersistence.now());
                String commandAction = switch (action) { case "CREATE" -> "RESERVE"; case "CHANGE" -> "REPLACE";
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
                log.setAggregateNo(root.getAppointmentNo()); log.setAction("APPOINTMENT_" + action + "_PREPARED");
                log.setOperatorRef(actor.operatorNo()); log.setExpectedRevision(root.getVersion());
                log.setResultRevision(root.getVersion()); log.setSafeSnapshot(requestEvidence);
                log.setRequestRef(op.getOperationNo()); db.actions.insert(log);
                current.setOperationNo(operationNo); AppointmentPersistence.require(db.currents.updateById(current)
                        == 1);
                return op;
            });
        } catch (DuplicateKeyException race) {
            var winner = find(preview.getEntityNo(), preview.getStoreNo(), key, hash);
            if (winner == null) { throw race; }
            return winner;
        }
    }
    private BtyAppointment createRoot(BtyAppointmentPreparation p) {
        var root = new BtyAppointment();
        root.setEntityNo(p.getEntityNo()); root.setStoreNo(p.getStoreNo());
        root.setAppointmentNo(BizKey.next("BTA")); root.setCustomerRef(p.getCustomerRef());
                root.setGuestRef(p.getGuestRef());
        root.setSource("CONSUMER".equals(p.getActorRealm()) ? "ONLINE" : "STAFF"); root.setStatus("HELD");
        root.setStartAt(p.getStartAt()); root.setEndAt(p.getEndAt()); root.setTimezone(p.getTimezone());
        root.setPolicyVersionNo(p.getPolicyVersionNo()); root.setVersion(1L); db.roots.insert(root);
        var current = new BtyAppointmentCurrent(); current.setEntityNo(root.getEntityNo());
        current.setAppointmentNo(root.getAppointmentNo()); current.setArrangementRevision(0L);
                db.currents.insert(current);
        var fence = new BtyAppointmentResourceFence(); fence.setEntityNo(root.getEntityNo());
        fence.setAppointmentNo(root.getAppointmentNo()); fence.setStoreNo(root.getStoreNo());
        fence.setFenceNo(BizKey.next("BTF")); db.fences.insert(fence);
        return root;
    }
}
