package ai.neargo.shop.beauty.service.impl;

import ai.neargo.shop.beauty.entity.BtyActionLog;
import ai.neargo.shop.beauty.entity.BtyServiceSubmission;
import ai.neargo.shop.beauty.entity.BtyServiceTask;
import ai.neargo.shop.beauty.entity.BtySourceAllocationGuard;
import ai.neargo.shop.beauty.entity.BtyTradeBinding;
import ai.neargo.shop.beauty.mapper.BeautyMappers;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.idem.IdempotencyScope;
import ai.neargo.shop.spi.trade.CheckoutPort;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Pure local allocation transaction. No owner/identity/resource port can be called while these
 * locks are held.
 */
public final class ServiceSourceBinding {
    private final AppointmentPersistence db;
    private final ServiceSubmissionQuery query;
    private final BeautyMappers.SourceObservationMapper observations;
    private final BeautyMappers.SourceAllocationGuardMapper guards;
    private final BeautyMappers.WorkOrderMapper orders;
    private final BeautyMappers.ServiceTaskMapper tasks;
    private final BeautyMappers.ServiceSubmissionMapper submissions;
    private final BeautyMappers.TradeBindingMapper bindings;
    private final Clock clock;

    public ServiceSourceBinding(
            AppointmentPersistence db,
            ServiceSubmissionQuery query,
            BeautyMappers.SourceObservationMapper observations,
            BeautyMappers.SourceAllocationGuardMapper guards,
            BeautyMappers.WorkOrderMapper orders,
            BeautyMappers.ServiceTaskMapper tasks,
            BeautyMappers.ServiceSubmissionMapper submissions,
            BeautyMappers.TradeBindingMapper bindings,
            Clock clock) {
        this.db = db;
        this.query = query;
        this.observations = observations;
        this.guards = guards;
        this.orders = orders;
        this.tasks = tasks;
        this.submissions = submissions;
        this.bindings = bindings;
        this.clock = clock;
    }

    public record Result(BtyServiceSubmission submission, String reason) {}

    /**
     * All ordinary blocking conditions roll back the entire allocation before becoming an explicit
     * pending reason.
     */
    public Result bind(
            BtyServiceSubmission intent,
            ServiceSubmissionServiceImpl.Command command,
            ServiceSourceObservation.Observed observed,
            String actor) {
        if (!observed.ready() || observed.facts() == null) {
            return new Result(null, "SOURCE_UNKNOWN");
        }
        try {
            return new Result(db.tx(() -> locked(intent, command, observed, actor)), null);
        } catch (Blocked blocked) {
            return new Result(null, blocked.reason);
        }
    }

    private BtyServiceSubmission locked(
            BtyServiceSubmission intent,
            ServiceSubmissionServiceImpl.Command command,
            ServiceSourceObservation.Observed observed,
            String actor) {
        String entity = intent.getEntityNo();
        String store = intent.getStoreNo();
        var facts = observed.facts();
        var fence = observations.selectCanonicalForUpdate(entity, facts.subOrderNo());
        require(
                fence != null
                        && store.equals(fence.getStoreNo())
                        && "READY".equals(fence.getState())
                        && fence.getObservationEpoch() == observed.ticket()
                        && fence.getSourceRevision() == facts.sourceRevision()
                        && fence.getVerifiedRevision().equals(fence.getSourceRevision())
                        && fence.getSourceHash().equals(facts.sourceHash())
                        && fence.getVerifiedHash().equals(facts.sourceHash())
                        && command.operationNo().equals(fence.getCheckoutOperationNo())
                        && command.buyerNo().equals(fence.getBuyerNo())
                        && facts.orderNo().equals(fence.getOrderNo()),
                "SOURCE_OBSERVATION_CHANGED");
        require(
                Arrays.asList(
                                db.json.readValue(
                                        fence.getVerifiedLines(),
                                        ServiceSourceObservation.QuantityLine[].class))
                        .equals(ServiceSourceObservation.quantityLines(facts)),
                "SOURCE_OBSERVATION_CHANGED");
        if ("EXISTING".equals(command.sourceMode())) {
            require(
                    command.expectedSourceNo().equals(facts.subOrderNo())
                            && command.expectedSourceRevision() == facts.sourceRevision()
                            && command.expectedSourceHash().equals(facts.sourceHash()),
                    "SOURCE_REVISION_CONFLICT");
        }
        var selectedLines = new TreeMap<String, CheckoutPort.SourceLine>();
        var taskLines = new TreeMap<String, CheckoutPort.SourceLine>();
        var amounts = new TreeMap<String, BigDecimal>();
        for (var task : command.tasks()) {
            var matches =
                    facts.lines().stream()
                            .filter(
                                    line ->
                                            "EXISTING".equals(command.sourceMode())
                                                    ? line.sourceLineNo()
                                                            .equals(task.sourceLineNo())
                                                    : line.clientLineRef()
                                                            .equals(task.clientLineRef()))
                            .toList();
            require(matches.size() == 1, "SOURCE_LINE_MISMATCH");
            var line = matches.getFirst();
            require(
                    task.goodsNo().equals(line.goodsNo())
                            && task.skuNo().equals(line.skuNo())
                            && task.uom().equals(line.uom()),
                    "SOURCE_LINE_MISMATCH");
            taskLines.put(task.taskNo(), line);
            selectedLines.put(line.sourceLineNo(), line);
            amounts.merge(line.sourceLineNo(), task.qty(), BigDecimal::add);
        }
        var lockedGuards = new TreeMap<String, BtySourceAllocationGuard>();
        for (var entry : selectedLines.entrySet()) {
            var line = entry.getValue();
            var guard =
                    guards.selectSourceForUpdate(
                            entity, "STORE_ORDER", facts.subOrderNo(), entry.getKey());
            if (guard == null) {
                guard = new BtySourceAllocationGuard();
                guard.setEntityNo(entity);
                guard.setGuardNo(BizKey.next("BTSG"));
                guard.setSourceKind("STORE_ORDER");
                guard.setSourceNo(facts.subOrderNo());
                guard.setSourceLineRef(entry.getKey());
                guard.setSourceRevision(facts.sourceRevision());
                guard.setSourceHash(facts.sourceHash());
                guard.setSourceQty(BigDecimal.valueOf(line.orderedQuantity()));
                guard.setAllocatedQty(BigDecimal.ZERO);
                guard.setUom("COUNT");
                guards.insert(guard);
                guard =
                        guards.selectSourceForUpdate(
                                entity, "STORE_ORDER", facts.subOrderNo(), entry.getKey());
            }
            require(
                    "COUNT".equals(guard.getUom())
                            && guard.getSourceRevision() <= facts.sourceRevision()
                            && (guard.getSourceRevision() != facts.sourceRevision()
                                    || guard.getSourceHash().equals(facts.sourceHash())),
                    "SOURCE_REVISION_CONFLICT");
            require(
                    guard.getSourceQty().compareTo(BigDecimal.valueOf(line.orderedQuantity())) == 0
                            || guard.getSourceRevision() < facts.sourceRevision(),
                    "SOURCE_QUANTITY_CHANGED");
            if (guard.getSourceRevision() < facts.sourceRevision()) {
                require(
                        guards.refreshSourceSnapshot(
                                        entity,
                                        guard.getGuardNo(),
                                        guard.getVersion(),
                                        guard.getSourceRevision(),
                                        facts.sourceRevision(),
                                        BigDecimal.valueOf(line.orderedQuantity()),
                                        facts.sourceHash(),
                                        actor,
                                        now())
                                == 1,
                        "SOURCE_REVISION_CONFLICT");
                guard =
                        guards.selectSourceForUpdate(
                                entity, "STORE_ORDER", facts.subOrderNo(), entry.getKey());
            }
            require(
                    guard.getAllocatedQty()
                                    .add(amounts.get(entry.getKey()))
                                    .compareTo(BigDecimal.valueOf(line.refundableQuantity()))
                            <= 0,
                    "SOURCE_QUANTITY_EXCEEDED");
            lockedGuards.put(entry.getKey(), guard);
        }
        var root = orders.selectScopedForUpdate(entity, store, intent.getWorkOrderNo());
        require(
                root != null
                        && root.getScopeRevision() == command.targetScopeRevision()
                        && !root.getScopeFrozen()
                        && List.of("OPEN", "IN_SERVICE").contains(root.getStatus()),
                "SCOPE_CHANGED");
        var current = query.root(entity, store, root.getWorkOrderNo());
        require(
                command.originalDraftNo().equals(current.draft().getSubmissionNo())
                        && command.originalScopeHash().equals(current.original().scopeHash())
                        && command.identityKind().equals(current.context().getIdentityKind())
                        && command.identityRef().equals(current.context().getIdentityRef()),
                "SCOPE_CHANGED");
        var lockedTasks = new ArrayList<BtyServiceTask>();
        for (var task :
                command.tasks().stream()
                        .sorted(
                                Comparator.comparing(
                                        ServiceSubmissionServiceImpl.FrozenTask::taskNo))
                        .toList()) {
            var row = tasks.selectScopedForUpdate(entity, store, task.taskNo());
            require(
                    row != null
                            && root.getWorkOrderNo().equals(row.getWorkOrderNo())
                            && current.tasks().stream()
                                    .anyMatch(
                                            candidate ->
                                                    candidate.getTaskNo().equals(row.getTaskNo()))
                            && row.getVersion() == task.revision()
                            && row.getBindingRevision() == 0
                            && row.getBindingRevision() == task.bindingRevision()
                            && "PENDING".equals(row.getStatus())
                            && row.getStartedAt() == null
                            && row.getDoneQty().signum() == 0
                            && row.getVoidedQty().signum() == 0
                            && task.qty().compareTo(row.getQty()) == 0
                            && task.uom().equals(row.getUom())
                            && task.skuNo().equals(row.getSkuNo())
                            && task.definitionVersionNo().equals(row.getDefinitionVersionNo())
                            && task.executionHash()
                                    .equals(
                                            IdempotencyScope.requestHash(
                                                    row.getExecutionSnapshot())),
                    "TASK_CHANGED");
            lockedTasks.add(row);
        }
        var submission = submissions.selectScopedForUpdate(entity, store, intent.getSubmissionNo());
        require(
                submission != null
                        && "RECOVERING".equals(submission.getStatus())
                        && submission.getVersion().equals(intent.getVersion())
                        && submission.getPayloadHash().equals(intent.getPayloadHash())
                        && db.json
                                .readTree(submission.getFrozenCommand())
                                .equals(db.json.readTree(intent.getFrozenCommand())),
                "SUBMISSION_LEASE_CHANGED");
        for (var entry : lockedGuards.entrySet()) {
            var guard = entry.getValue();
            var line = selectedLines.get(entry.getKey());
            require(
                    guards.allocateObservedQuantity(
                                    entity,
                                    store,
                                    guard.getGuardNo(),
                                    guard.getVersion(),
                                    facts.sourceRevision(),
                                    facts.sourceHash(),
                                    observed.ticket(),
                                    amounts.get(entry.getKey()),
                                    BigDecimal.valueOf(line.refundableQuantity()),
                                    actor,
                                    now())
                            == 1,
                    "SOURCE_QUANTITY_EXCEEDED");
        }
        for (var task : lockedTasks) {
            var line = taskLines.get(task.getTaskNo());
            var binding = new BtyTradeBinding();
            binding.setEntityNo(entity);
            binding.setBindingNo(BizKey.next("BTTB"));
            binding.setSubmissionNo(submission.getSubmissionNo());
            binding.setTaskNo(task.getTaskNo());
            binding.setBindingRevision(1L);
            binding.setSourceKind("STORE_ORDER");
            binding.setSourceNo(facts.subOrderNo());
            binding.setSourceLineRef(line.sourceLineNo());
            binding.setSourceRevision(facts.sourceRevision());
            binding.setSourceSnapshotHash(facts.sourceHash());
            binding.setQty(task.getQty());
            binding.setUom(task.getUom());
            binding.setGuardNo(lockedGuards.get(line.sourceLineNo()).getGuardNo());
            bindings.insert(binding);
            require(
                    tasks.switchBindingRevision(
                                    entity,
                                    store,
                                    task.getTaskNo(),
                                    task.getVersion(),
                                    0,
                                    1,
                                    actor,
                                    now())
                            == 1,
                    "TASK_CHANGED");
        }
        require(
                orders.touchSourceBinding(
                                entity,
                                store,
                                root.getWorkOrderNo(),
                                root.getVersion(),
                                command.targetScopeRevision(),
                                actor,
                                now())
                        == 1,
                "SCOPE_CHANGED");
        require(
                submissions.finishSourceRecovery(
                                entity,
                                store,
                                submission.getSubmissionNo(),
                                submission.getVersion(),
                                "BOUND",
                                null,
                                null,
                                now(),
                                actor)
                        == 1,
                "SUBMISSION_LEASE_CHANGED");
        var action = new BtyActionLog();
        action.setEntityNo(entity);
        action.setStoreNo(store);
        action.setActionNo(BizKey.next("BTAL"));
        action.setAggregateType("BEAUTY_SOURCE_SUBMISSION");
        action.setAggregateNo(submission.getSubmissionNo());
        action.setAction("SOURCE_BOUND");
        action.setOperatorRef(actor);
        action.setRequestRef(submission.getSubmissionNo());
        action.setExpectedRevision(submission.getVersion());
        action.setResultRevision(submission.getVersion() + 1);
        action.setSafeSnapshot(
                db.json.writeValueAsString(
                        Map.of(
                                "sourceNo",
                                facts.subOrderNo(),
                                "operationNo",
                                command.operationNo(),
                                "sourceRevision",
                                facts.sourceRevision(),
                                "sourceHash",
                                facts.sourceHash(),
                                "taskNos",
                                lockedTasks.stream().map(BtyServiceTask::getTaskNo).toList())));
        db.actions.insert(action);
        return submissions.selectScopedForUpdate(entity, store, submission.getSubmissionNo());
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private void require(boolean condition, String reason) {
        if (!condition) {
            throw new Blocked(reason);
        }
    }

    private static final class Blocked extends RuntimeException {
        private final String reason;

        private Blocked(String reason) {
            super(reason);
            this.reason = reason;
        }
    }
}
