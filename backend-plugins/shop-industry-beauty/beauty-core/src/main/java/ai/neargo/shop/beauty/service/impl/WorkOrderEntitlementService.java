package ai.neargo.shop.beauty.service.impl;

import static ai.neargo.shop.beauty.service.impl.AppointmentPersistence.require;

import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.beauty.entity.BtyServiceSubmission;
import ai.neargo.shop.beauty.entity.BtySourceAllocationGuard;
import ai.neargo.shop.beauty.entity.BtyTradeBinding;
import ai.neargo.shop.beauty.mapper.BeautyMappers;
import ai.neargo.shop.beauty.policy.AppointmentAccess;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.idem.IdempotencyScope;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.AssetKind;
import ai.neargo.shop.spi.entitlement.ServiceWorkOrderOriginPort.Origin;
import ai.neargo.shop.spi.entitlement.ServiceWorkOrderUseFactsPort;
import ai.neargo.shop.spi.entitlement.ServiceWorkOrderUseFactsPort.Fact;
import ai.neargo.shop.spi.entitlement.ServiceWorkOrderUsePort;
import ai.neargo.shop.spi.entitlement.SourceRef;
import ai.neargo.shop.spi.user.MerchantOperatorAccessPort;
import java.math.BigDecimal;
import java.util.List;

/** External facts are read before the source guard, root, task and original journal locks. */
public final class WorkOrderEntitlementService implements ServiceWorkOrderUsePort {
    private final AppointmentPersistence db;
    private final AppointmentAccess access;
    private final ServiceSubmissionQuery query;
    private final BeautyMappers.WorkOrderMapper orders;
    private final BeautyMappers.ServiceTaskMapper tasks;
    private final BeautyMappers.ServiceSubmissionMapper submissions;
    private final BeautyMappers.SourceAllocationGuardMapper guards;
    private final BeautyMappers.TradeBindingMapper bindings;
    private final ServiceWorkOrderUseFactsPort facts;
    private final MerchantOperatorAccessPort operators;

    public WorkOrderEntitlementService(AppointmentPersistence db, AppointmentAccess access,
            ServiceSubmissionQuery query, BeautyMappers.WorkOrderMapper orders, BeautyMappers.ServiceTaskMapper tasks,
            BeautyMappers.ServiceSubmissionMapper submissions, BeautyMappers.SourceAllocationGuardMapper guards,
            BeautyMappers.TradeBindingMapper bindings, ServiceWorkOrderUseFactsPort facts,
            MerchantOperatorAccessPort operators) {
        this.db = db; this.access = access; this.query = query; this.orders = orders; this.tasks = tasks;
        this.submissions = submissions; this.guards = guards; this.bindings = bindings;
        this.facts = facts; this.operators = operators;
    }

    public record Frozen(Lookup lookup, String operatorNo, SourceRef source, Origin origin, String sourceHash,
                         AssetKind assetKind, long amountMinor) {
        static Frozen of(Fact fact) {
            return new Frozen(fact.lookup(), fact.operatorNo(), fact.source(), fact.origin(), fact.sourceHash(),
                    fact.assetKind(), fact.amountMinor());
        }
    }

    @Override
    public Binding reserve(EntitlementContext context, Lookup lookup) {
        if (db.read(submissions::entitlementInstalled) != 1) {
            throw BizException.of(ErrorCode.BEAUTY_DEPENDENCY_UNAVAILABLE);
        }
        require("MAIN".equals(lookup.tenantNo()) && context.tenantNo().equals(lookup.tenantNo()));
        access.merchant(lookup.entityNo(), lookup.storeNo(), context.actorNo(), true);
        var user = access.current(context.actorNo());
        var fact = facts.inspect(lookup);
        require(lookup.equals(fact.lookup()) && context.actorNo().equals(fact.operatorNo()));
        String permission = fact.assetKind() == AssetKind.STORED_VALUE ? BizPerms.SV_CONSUME_CREATE : BizPerms.ENT_USE;
        if (!operators.allowed(new MerchantOperatorAccessPort.Query(user.tenantNo(), lookup.entityNo(),
                lookup.storeNo(), user.userNo(), user.realm().name(), user.subjectKind().name(), permission))) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        var frozen = Frozen.of(fact);
        var prior = db.read(() -> original(lookup));
        if (prior != null) {
            require(frozen.equals(readFrozen(prior)));
            return view(prior);
        }
        require(fact.state() == ServiceWorkOrderUseFactsPort.State.AUTHORIZED && fact.assetKind() != null
                && List.of(AssetKind.PAID_CARD, AssetKind.STORE_EXCHANGE, AssetKind.STORED_VALUE)
                        .contains(fact.assetKind()));
        return db.tx(() -> {
            var guard = guard(frozen, true);
            var root = orders.selectScopedForUpdate(lookup.entityNo(), lookup.storeNo(), frozen.origin().workOrderNo());
            var task = tasks.selectScopedForUpdate(lookup.entityNo(), lookup.storeNo(), frozen.origin().taskNo());
            var replay = original(lookup);
            if (replay != null) {
                require(frozen.equals(readFrozen(replay)));
                return view(replay);
            }
            require(root != null && task != null && !root.getScopeFrozen()
                    && root.getScopeRevision() == frozen.origin().scopeRevision()
                    && List.of("OPEN", "IN_SERVICE").contains(root.getStatus())
                    && root.getWorkOrderNo().equals(task.getWorkOrderNo())
                    && task.getVersion() == frozen.origin().taskRevision() && task.getBindingRevision() == 0
                    && "PENDING".equals(task.getStatus()) && task.getStartedAt() == null
                    && task.getDoneQty().signum() == 0 && task.getVoidedQty().signum() == 0
                    && "COUNT".equals(task.getUom()) && task.getQty().compareTo(quantity(frozen)) == 0
                    && frozen.origin().skuNo().equals(task.getSkuNo())
                    && submissions.selectActiveForWorkOrder(lookup.entityNo(), lookup.storeNo(),
                            root.getWorkOrderNo()).isEmpty()
                    && submissions.protectedTaskStarts(lookup.entityNo(), lookup.storeNo(), task.getTaskNo()) == 0);
            var scope = query.root(lookup.entityNo(), lookup.storeNo(), root.getWorkOrderNo());
            require("USER".equals(scope.context().getIdentityKind())
                    && frozen.origin().userNo().equals(scope.context().getIdentityRef()));
            require(guard.getAllocatedQty().signum() == 0);
            var row = new BtyServiceSubmission();
            row.setEntityNo(lookup.entityNo()); row.setStoreNo(lookup.storeNo());
            row.setWorkOrderNo(root.getWorkOrderNo()); row.setSubmissionNo(BizKey.next("BTEU"));
            row.setAction("ENTITLEMENT_USE"); row.setStatus("RESERVED");
            row.setRequestKeyHash(IdempotencyScope.requestHash("BEAUTY_ENTITLEMENT_USE", lookup.operationNo()));
            row.setPayloadHash(IdempotencyScope.requestHash(db.json.writeValueAsString(frozen)));
            row.setFrozenCommand(db.json.writeValueAsString(frozen));
            row.setEntitlementTaskNo(task.getTaskNo()); row.setEntitlementActiveTaskNo(task.getTaskNo());
            row.setEntitlementOperationNo(lookup.operationNo());
            row.setCreatedBy(context.actorNo()); row.setUpdatedBy(context.actorNo());
            require(submissions.insert(row) == 1);
            require(tasks.reserveEntitlementRevision(lookup.entityNo(), task.getTaskNo(), task.getVersion(),
                    context.actorNo(), AppointmentPersistence.now()) == 1);
            require(orders.touchSourceBinding(lookup.entityNo(), lookup.storeNo(), root.getWorkOrderNo(),
                    root.getVersion(), root.getScopeRevision(), context.actorNo(), AppointmentPersistence.now()) == 1);
            return view(row);
        });
    }

    @Override
    public Binding inspect(Lookup lookup) {
        if (db.read(submissions::entitlementInstalled) != 1) {
            throw BizException.of(ErrorCode.BEAUTY_DEPENDENCY_UNAVAILABLE);
        }
        require("MAIN".equals(lookup.tenantNo()));
        return db.read(() -> {
            var row = original(lookup);
            return row == null ? new Binding(lookup, null, null, null, State.NOT_FOUND, null, null) : view(row);
        });
    }

    @Override
    public Binding reconcile(Lookup lookup) {
        if (db.read(submissions::entitlementInstalled) != 1) {
            throw BizException.of(ErrorCode.BEAUTY_DEPENDENCY_UNAVAILABLE);
        }
        require("MAIN".equals(lookup.tenantNo()));
        var fact = facts.inspect(lookup);
        require(lookup.equals(fact.lookup()));
        var frozen = Frozen.of(fact);
        var before = db.read(() -> original(lookup));
        if (before == null) {
            require(fact.state() == ServiceWorkOrderUseFactsPort.State.RELEASED);
            return new Binding(lookup, null, frozen.origin(), frozen.sourceHash(), State.RELEASED, null, null);
        }
        require(frozen.equals(readFrozen(before)));
        if (fact.state() != ServiceWorkOrderUseFactsPort.State.COMMITTED
                && fact.state() != ServiceWorkOrderUseFactsPort.State.RELEASED) return view(before);
        return db.tx(() -> {
            var guard = guard(frozen, false);
            var root = orders.selectScopedForUpdate(lookup.entityNo(), lookup.storeNo(), frozen.origin().workOrderNo());
            var task = tasks.selectScopedForUpdate(lookup.entityNo(), lookup.storeNo(), frozen.origin().taskNo());
            var row = submissions.selectScopedForUpdate(lookup.entityNo(), lookup.storeNo(), before.getSubmissionNo());
            require(root != null && task != null && row != null && frozen.equals(readFrozen(row)));
            if (!"RESERVED".equals(row.getStatus())) {
                var recorded = view(row);
                require(fact.state() == ServiceWorkOrderUseFactsPort.State.COMMITTED
                        ? recorded.state() == State.BOUND && fact.usageNo().equals(recorded.usageNo())
                        : recorded.state() == State.RELEASED);
                return recorded;
            }
            String actor = frozen.operatorNo();
            if (fact.state() == ServiceWorkOrderUseFactsPort.State.RELEASED) {
                require(guard.getAllocatedQty().signum() == 0);
                require(submissions.settleEntitlement(lookup.entityNo(), lookup.storeNo(), row.getSubmissionNo(),
                        row.getVersion(), "RELEASED", null, null, actor, AppointmentPersistence.now()) == 1);
                return new Binding(lookup, row.getSubmissionNo(), frozen.origin(), frozen.sourceHash(),
                        State.RELEASED, null, null);
            }
            require(root.getScopeRevision() == frozen.origin().scopeRevision() && !root.getScopeFrozen()
                    && List.of("OPEN", "IN_SERVICE").contains(root.getStatus())
                    && root.getWorkOrderNo().equals(task.getWorkOrderNo())
                    && task.getVersion() == frozen.origin().taskRevision() + 1 && task.getBindingRevision() == 0
                    && task.getDoneQty().signum() == 0 && task.getVoidedQty().signum() == 0
                    && "PENDING".equals(task.getStatus()) && task.getQty().compareTo(quantity(frozen)) == 0);
            require(guards.consumeEntitlement(lookup.entityNo(), guard.getGuardNo(), guard.getVersion(),
                    quantity(frozen), frozen.sourceHash(), actor, AppointmentPersistence.now()) == 1);
            var binding = new BtyTradeBinding();
            binding.setEntityNo(lookup.entityNo()); binding.setBindingNo(BizKey.next("BTTB"));
            binding.setSubmissionNo(row.getSubmissionNo()); binding.setTaskNo(task.getTaskNo());
            binding.setBindingRevision(1L); binding.setSourceKind("SERVICE_USE");
            binding.setSourceNo(frozen.source().sourceNo()); binding.setSourceLineRef("SL1");
            binding.setSourceRevision(frozen.source().sourceRevision());
            binding.setSourceSnapshotHash(frozen.sourceHash()); binding.setAdmissionKind("ENTITLEMENT_USE");
            binding.setQty(quantity(frozen)); binding.setUom("COUNT"); binding.setGuardNo(guard.getGuardNo());
            binding.setCreatedBy(actor); binding.setUpdatedBy(actor);
            require(bindings.insert(binding) == 1);
            require(tasks.switchBindingRevision(lookup.entityNo(), lookup.storeNo(), task.getTaskNo(),
                    task.getVersion(), 0, 1, actor, AppointmentPersistence.now()) == 1);
            require(orders.touchSourceBinding(lookup.entityNo(), lookup.storeNo(), root.getWorkOrderNo(),
                    root.getVersion(), root.getScopeRevision(), actor, AppointmentPersistence.now()) == 1);
            var result = new Binding(lookup, row.getSubmissionNo(), frozen.origin(), frozen.sourceHash(),
                    State.BOUND, binding.getBindingNo(), fact.usageNo());
            require(submissions.settleEntitlement(lookup.entityNo(), lookup.storeNo(), row.getSubmissionNo(),
                    row.getVersion(), "BOUND", fact.usageNo(), db.json.writeValueAsString(result),
                    actor, AppointmentPersistence.now()) == 1);
            return result;
        });
    }

    private BtyServiceSubmission original(Lookup lookup) {
        return submissions.entitlementOriginal(lookup.entityNo(), lookup.storeNo(), lookup.operationNo());
    }

    private Frozen readFrozen(BtyServiceSubmission row) {
        return db.json.readValue(row.getFrozenCommand(), Frozen.class);
    }

    private Binding view(BtyServiceSubmission row) {
        var frozen = readFrozen(row);
        if ("BOUND".equals(row.getStatus())) {
            var result = db.json.readValue(row.getEntitlementReceiptJson(), Binding.class);
            require(frozen.lookup().equals(result.lookup()) && frozen.origin().equals(result.origin())
                    && frozen.sourceHash().equals(result.sourceHash())
                    && row.getEntitlementUsageNo().equals(result.usageNo())
                    && row.getSubmissionNo().equals(result.claimNo()) && result.state() == State.BOUND);
            return result;
        }
        return new Binding(frozen.lookup(), row.getSubmissionNo(), frozen.origin(), frozen.sourceHash(),
                State.valueOf(row.getStatus()), null, null);
    }

    private BtySourceAllocationGuard guard(Frozen frozen, boolean create) {
        var row = guards.selectSourceForUpdate(frozen.lookup().entityNo(), "SERVICE_USE",
                frozen.source().sourceNo(), "SL1");
        if (row == null && create) {
            row = new BtySourceAllocationGuard();
            row.setEntityNo(frozen.lookup().entityNo()); row.setGuardNo(BizKey.next("BTSG"));
            row.setSourceKind("SERVICE_USE"); row.setSourceNo(frozen.source().sourceNo());
            row.setSourceLineRef("SL1"); row.setSourceRevision(frozen.source().sourceRevision());
            row.setSourceHash(frozen.sourceHash()); row.setSourceQty(quantity(frozen));
            row.setAllocatedQty(BigDecimal.ZERO); row.setUom("COUNT"); row.setAdmissionKind("ENTITLEMENT_USE");
            require(guards.insert(row) == 1);
        }
        require(row != null && "ENTITLEMENT_USE".equals(row.getAdmissionKind())
                && frozen.sourceHash().equals(row.getSourceHash())
                && row.getSourceQty().compareTo(quantity(frozen)) == 0);
        return row;
    }

    private static BigDecimal quantity(Frozen frozen) {
        return BigDecimal.valueOf(frozen.origin().quantity());
    }
}
