package ai.neargo.shop.beauty.service.impl;

import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.beauty.entity.BtyAppointmentArrangement;
import ai.neargo.shop.beauty.entity.BtyAppointmentOperation;
import ai.neargo.shop.beauty.entity.BtyGuestClaimReceipt;
import ai.neargo.shop.beauty.entity.BtyServiceExecutionFence;
import ai.neargo.shop.beauty.entity.BtyServiceSubmission;
import ai.neargo.shop.beauty.entity.BtyServiceTask;
import ai.neargo.shop.beauty.entity.BtySourceObservation;
import ai.neargo.shop.beauty.entity.BtyTradeBinding;
import ai.neargo.shop.beauty.entity.BtyWorkOrder;
import ai.neargo.shop.beauty.mapper.BeautyMappers;
import ai.neargo.shop.beauty.policy.DirectTaskPlanSchemaVerifier;
import ai.neargo.shop.beauty.policy.ServiceExecutionAccess;
import ai.neargo.shop.beauty.policy.ServiceExecutionAccess.Current;
import ai.neargo.shop.beauty.policy.ServiceExecutionSchemaVerifier;
import ai.neargo.shop.beauty.service.ServiceTaskExecutionService;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.idem.IdempotencyScope;
import ai.neargo.shop.spi.resource.ResourceBookingPort;
import ai.neargo.shop.spi.resource.ResourcePlanContract;
import ai.neargo.shop.spi.trade.ServiceExecutionContract;
import ai.neargo.shop.spi.trade.ServiceExecutionPort;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionBinding;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionLookup;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.Grant;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.PrepareExecution;
import ai.neargo.shop.spi.trade.ServiceExecutionSourcePort.FreezeStart;
import ai.neargo.shop.spi.trade.ServiceExecutionSourcePort.IntentState;
import ai.neargo.shop.spi.trade.ServiceExecutionSourcePort.StartIntent;
import ai.neargo.shop.spi.trade.ServiceExecutionSourcePort.StartIntentLookup;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * A committed local original precedes every slow owner call; quantity evidence never writes
 * participation.
 */
public final class ServiceTaskExecutionServiceImpl implements ServiceTaskExecutionService {
    private final AppointmentPersistence db;
    private final ServiceSubmissionQuery query;
    private final ServiceExecutionAccess access;
    private final ServiceExecutionSchemaVerifier schema;
    private final BeautyMappers.SourceObservationMapper observations;
    private final BeautyMappers.SourceAllocationGuardMapper guards;
    private final BeautyMappers.WorkOrderMapper orders;
    private final BeautyMappers.ServiceTaskMapper tasks;
    private final BeautyMappers.TradeBindingMapper bindings;
    private final BeautyMappers.ServiceExecutionFenceMapper fences;
    private final BeautyMappers.ServiceSubmissionMapper submissions;
    private final ObjectProvider<ServiceExecutionPort> owner;
    private final Clock clock;
    private final TaskStartOriginalReader originals;
    private DirectTaskPlanSchemaVerifier directPlans;

    /**
     * DIRECT pointers require the actual forward schema; old appointment originals keep their V1
     * gate.
     */
    public void setDirectPlanSchema(DirectTaskPlanSchemaVerifier directPlans) {
        this.directPlans = Objects.requireNonNull(directPlans);
    }

    private ObjectProvider<ServiceTaskStockPreparationCoordinator> stockObservation;
    private final BeautyMappers.GuestClaimReceiptMapper claims;

    public ServiceTaskExecutionServiceImpl(
            AppointmentPersistence db,
            ServiceSubmissionQuery query,
            ServiceExecutionAccess access,
            ServiceExecutionSchemaVerifier schema,
            BeautyMappers.SourceObservationMapper observations,
            BeautyMappers.SourceAllocationGuardMapper guards,
            BeautyMappers.WorkOrderMapper orders,
            BeautyMappers.ServiceTaskMapper tasks,
            BeautyMappers.TradeBindingMapper bindings,
            BeautyMappers.ServiceExecutionFenceMapper fences,
            BeautyMappers.ServiceSubmissionMapper submissions,
            BeautyMappers.GuestClaimReceiptMapper claims,
            ObjectProvider<ServiceExecutionPort> owner,
            Clock clock,
            TaskStartOriginalReader originals) {
        this.originals = originals;
        this.db = db;
        this.query = query;
        this.access = access;
        this.schema = schema;
        this.observations = observations;
        this.guards = guards;
        this.orders = orders;
        this.tasks = tasks;
        this.bindings = bindings;
        this.fences = fences;
        this.submissions = submissions;
        this.owner = owner;
        this.clock = clock;
        this.claims = claims;
    }

    /**
     * Production configuration attaches the companion observer to this existing candidate lease.
     */
    public void setStockObservationProvider(
            ObjectProvider<ServiceTaskStockPreparationCoordinator> stockObservation) {
        this.stockObservation = Objects.requireNonNull(stockObservation);
    }

    /** Immutable START envelope is separate from legacy SUBMIT_SOURCE/ADD_SOURCE schema parsers. */
    public record StartCommand(
            String schema,
            String requestHash,
            String requestKeyHash,
            String bindingHash,
            StartIntent intent,
            String actionNo,
            String actionHash) {}

    /**
     * Exact booking bytes/hash and persisted lineage survive selection of the task's original
     * demand groups.
     */
    public record ExecutionPlan(
            String schema,
            String appointmentNo,
            String appointmentItemNo,
            long arrangementRevision,
            String arrangementHash,
            HoldEvidence holdEvidence,
            String bookingPlan,
            String bookingPlanHash,
            String taskSnapshotHash,
            String taskPlan,
            String taskPlanHash) {}

    @Override
    public ExecutionPreparation prepareTask(FreezeStart request) {
        var intent = freeze(request);
        if (intent.state() == IntentState.REJECTED) {
            return new ExecutionPreparation(intent.binding(), null, "TASK_CHANGED");
        }
        var lookup = new StartIntentLookup(intent.binding());
        var current = access.requireSelf(request.scope());
        authorizeTask(intent.binding(), current);
        var claimed = claim(lookup, current.identity().actorNo());
        if (claimed == null)
            return new ExecutionPreparation(intent.binding(), null, "SOURCE_SUSPENDED");
        var provider = owner.getIfAvailable();
        if (provider == null)
            return new ExecutionPreparation(
                    intent.binding(), null, "RESOURCE_EXECUTION_UNAVAILABLE");
        var command = claimed.command();
        Grant grant;
        try {
            grant =
                    provider.prepare(
                            new PrepareExecution(
                                    intent.binding(),
                                    command.actionNo(),
                                    command.requestKeyHash(),
                                    command.actionHash()));
        } catch (RuntimeException unavailable) {
            // The committed original and claimed epoch remain; failure is never a fabricated owner
            // rejection.
            return new ExecutionPreparation(
                    intent.binding(), null, "RESOURCE_EXECUTION_UNAVAILABLE");
        }
        access.requireSame(request.scope(), current);
        return apply(claimed, grant, current.identity().actorNo());
    }

    @Override
    public ExecutionPreparation queryTask(StartIntentLookup request) {
        DelegatedIdentityContext.outside();
        schema.requireAvailable();
        var current = access.requireSelf(request.binding().scope());
        authorizeTask(request.binding(), current);
        var claimed = claim(new StartIntentLookup(request.binding()), current.identity().actorNo());
        if (claimed == null)
            return new ExecutionPreparation(request.binding(), null, "SOURCE_SUSPENDED");
        var provider = owner.getIfAvailable();
        if (provider == null)
            return new ExecutionPreparation(
                    request.binding(), null, "RESOURCE_EXECUTION_UNAVAILABLE");
        Grant grant;
        try {
            grant = provider.query(new ExecutionLookup(request.binding()));
        } catch (RuntimeException unavailable) {
            return new ExecutionPreparation(
                    request.binding(), null, "RESOURCE_EXECUTION_UNAVAILABLE");
        }
        access.requireSame(request.binding().scope(), current);
        return apply(claimed, grant, current.identity().actorNo());
    }

    /** Source SPI entry: no Core IO occurs before or within this short primary transaction. */
    @Override
    public StartIntent freeze(FreezeStart request) {
        DelegatedIdentityContext.outside();
        schema.requireAvailable();
        var current = access.requireSelf(request.scope());
        var candidates =
                db.read(
                        () ->
                                tasks.selectForWorkOrder(
                                        request.scope().entityNo(),
                                        request.scope().storeNo(),
                                        request.workOrderNo()));
        var candidate =
                candidates.stream()
                        .filter(t -> request.taskNo().equals(t.getTaskNo()))
                        .findFirst()
                        .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND));
        var prior =
                db.read(
                        () ->
                                fences.findTask(
                                        request.scope().entityNo(),
                                        request.scope().storeNo(),
                                        request.taskNo()));
        if (prior == null) requireTask(candidate);
        var root =
                query.root(
                        request.scope().entityNo(),
                        request.scope().storeNo(),
                        request.workOrderNo());
        var task =
                root.tasks().stream()
                        .filter(t -> request.taskNo().equals(t.getTaskNo()))
                        .findFirst()
                        .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND));
        requireAssigned(task, current);
        String expectedSchema =
                task.getAppointmentItemNo() == null ? "START_DIRECT_V2" : "START_MANUAL_V1";
        var selected =
                db.read(
                        () ->
                                bindings.selectByTaskRevisions(
                                        request.scope().entityNo(),
                                        request.scope().storeNo(),
                                        List.of(request.taskNo()),
                                        request.expectedBindingRevision()));
        require(selected.size() == 1);
        var binding = selected.getFirst();
        access.requireSame(request.scope(), current);
        try {
            return db.tx(() -> freezeLocked(request, current, binding, expectedSchema));
        } catch (DuplicateKeyException collision) {
            // Resolve collisions only by complete original replay; never invent success on a losing
            // unit/key.
            return db.tx(() -> freezeLocked(request, current, binding, expectedSchema));
        }
    }

    private StartIntent freezeLocked(
            FreezeStart request, Current current, BtyTradeBinding selected, String expectedSchema) {
        String entity = request.scope().entityNo(), store = request.scope().storeNo();
        var observation = observations.selectCanonicalForUpdate(entity, selected.getSourceNo());
        require(
                observation != null
                        && store.equals(observation.getStoreNo())
                        && "MANUAL_OFFLINE".equals(observation.getAdmissionKind())
                        && "STORE_ORDER".equals(observation.getSourceKind())
                        && "BEAUTY".equals(observation.getCheckoutCaller()));
        var guard =
                guards.selectSourceForUpdate(
                        entity, "STORE_ORDER", selected.getSourceNo(), selected.getSourceLineRef());
        require(guard != null && guard.getGuardNo().equals(selected.getGuardNo()));
        var order = orders.selectScopedForUpdate(entity, store, request.workOrderNo());
        var task = tasks.selectScopedForUpdate(entity, store, request.taskNo());
        require(
                order != null
                        && task != null
                        && request.workOrderNo().equals(task.getWorkOrderNo()));
        require(
                expectedSchema.equals(
                        task.getAppointmentItemNo() == null
                                ? "START_DIRECT_V2"
                                : "START_MANUAL_V1"));
        var prior = fences.findTask(entity, store, request.taskNo());
        if (prior != null) {
            prior = fences.lockOriginal(entity, store, prior.getExecutionNo());
            var start = submissions.selectScopedForUpdate(entity, store, prior.getSubmissionNo());
            var original = command(start);
            require(expectedSchema.equals(original.schema()));
            require(
                    requestHash(request, current, "START_DIRECT_V2".equals(original.schema()))
                            .equals(original.requestHash()));
            requireAssigned(task, current);
            return projection(prior, original, observation);
        }
        require(
                order.getScopeRevision() == request.expectedScopeRevision()
                        && !order.getScopeFrozen()
                        && List.of("OPEN", "IN_SERVICE").contains(order.getStatus())
                        && task.getVersion() == request.expectedTaskRevision()
                        && task.getBindingRevision() == request.expectedBindingRevision()
                        && task.getScopeRevision() <= order.getScopeRevision());
        requireTask(task);
        var root = query.root(entity, store, order.getWorkOrderNo());
        var actual =
                bindings.selectByTaskRevisions(
                        entity, store, List.of(task.getTaskNo()), task.getBindingRevision());
        require(actual.size() == 1);
        var source = actual.getFirst();
        require(
                source.getBindingNo().equals(selected.getBindingNo())
                        && source.getVersion().equals(selected.getVersion())
                        && "MANUAL_OFFLINE".equals(source.getAdmissionKind())
                        && "STORE_ORDER".equals(source.getSourceKind())
                        && source.getQty().compareTo(BigDecimal.ONE) == 0
                        && "COUNT".equals(source.getUom())
                        && source.getSourceNo().equals(observation.getSourceNo())
                        && Objects.equals(
                                source.getManualSourceRevision(), observation.getManualRevision())
                        && Objects.equals(source.getManualSourceHash(), observation.getManualHash())
                        && Objects.equals(
                                guard.getManualSourceRevision(), observation.getManualRevision())
                        && Objects.equals(guard.getManualSourceHash(), observation.getManualHash())
                        && "MANUAL_OFFLINE".equals(guard.getAdmissionKind())
                        && Objects.equals(source.getCustomerKind(), observation.getCustomerKind())
                        && Objects.equals(source.getBuyerNo(), observation.getBuyerNo())
                        && Objects.equals(source.getGuestNo(), observation.getGuestNo())
                        && Objects.equals(
                                source.getIdentityRevision(), observation.getIdentityRevision())
                        && Objects.equals(guard.getCustomerKind(), source.getCustomerKind())
                        && Objects.equals(guard.getBuyerNo(), source.getBuyerNo())
                        && Objects.equals(guard.getGuestNo(), source.getGuestNo())
                        && Objects.equals(
                                guard.getIdentityRevision(), source.getIdentityRevision()));
        require(
                "READY".equals(observation.getManualState())
                        && "ADMITTED".equals(observation.getManualAdmissionState())
                        && observation.getManualObservedAt() != null
                        && observation.getManualEpoch() > 0
                        && Objects.equals(
                                observation.getManualRevision(),
                                observation.getManualVerifiedRevision())
                        && Objects.equals(
                                observation.getManualHash(), observation.getManualVerifiedHash()));
        requireParty(root, observation);
        var lines =
                db.json.readValue(
                        observation.getManualVerifiedLines(),
                        ServiceSourceObservation.ManualQuantityLine[].class);
        var matching =
                Arrays.stream(lines)
                        .filter(l -> source.getSourceLineRef().equals(l.sourceLineNo()))
                        .toList();
        require(matching.size() == 1);
        var line = matching.getFirst();
        require(
                task.getSkuNo().equals(line.skuNo())
                        && "COUNT".equals(line.uom())
                        && line.orderedQuantity() > 0
                        && line.orderedQuantity() <= 999
                        && guard.getSourceQty()
                                        .compareTo(BigDecimal.valueOf(line.orderedQuantity()))
                                == 0
                        && guard.getAllocatedQty().compareTo(BigDecimal.ONE) >= 0);
        if (order.getAppointmentNo() != null) {
            require(
                    root.tasks().stream()
                                    .filter(
                                            t ->
                                                    Objects.equals(
                                                            t.getAppointmentItemNo(),
                                                            task.getAppointmentItemNo()))
                                    .count()
                            == 1);
        }
        var plan = plan(root, task, current);
        String planJson = ServiceExecutionContract.canonical(plan);
        boolean direct = plan instanceof DirectTaskPlan.ExecutionPlan;
        String planHash = direct ? directPlanHash(planJson) : planHash(planJson);
        var history = fences.allLineHistory(source.getSourceLineRef());
        var used = new HashSet<Integer>();
        history.forEach(row -> used.add(row.getUnitOrdinal()));
        int ordinal = 1;
        while (ordinal <= line.orderedQuantity() && used.contains(ordinal)) ordinal++;
        if (ordinal > line.orderedQuantity()) throw BizException.of(ErrorCode.CONFLICT);
        String execution = BizKey.next("BTEX"),
                fence = BizKey.next("BTEF"),
                submission = BizKey.next("BTSU");
        String resource = BizKey.next("BTRC"), action = BizKey.next("BTEA");
        String resourceHash =
                IdempotencyScope.requestHash(
                        direct
                                ? "BEAUTY_DIRECT_EXECUTION_RESOURCE_V2"
                                : "BEAUTY_EXECUTION_RESOURCE_V1",
                        execution,
                        resource,
                        planHash,
                        source.getBindingNo(),
                        Integer.toString(ordinal));
        var provisional =
                new ExecutionBinding(
                        request.scope(),
                        "BEAUTY",
                        observation.getCheckoutOperationNo(),
                        observation.getOrderNo(),
                        observation.getSourceNo(),
                        source.getSourceLineRef(),
                        ordinal,
                        order.getWorkOrderNo(),
                        order.getScopeRevision(),
                        task.getTaskNo(),
                        task.getVersion(),
                        source.getBindingNo(),
                        source.getBindingRevision(),
                        execution,
                        1,
                        submission,
                        fence,
                        1,
                        observation.getManualEpoch(),
                        observation.getManualRevision(),
                        observation.getManualHash(),
                        task.getDefinitionVersionNo(),
                        IdempotencyScope.requestHash(task.getExecutionSnapshot()),
                        planHash,
                        "0".repeat(64));
        var binding =
                withPayload(
                        provisional,
                        ServiceExecutionContract.payloadHash(
                                provisional,
                                current.personnel(),
                                current.namespaceHash(),
                                resource,
                                resourceHash));
        var intent =
                new StartIntent(
                        binding,
                        IntentState.FROZEN,
                        1,
                        "MANUAL_OFFLINE",
                        IdempotencyScope.requestHash(
                                "BEAUTY_EXECUTION_PARTY_V1",
                                ServiceExecutionContract.canonical(
                                        ServiceSourceObservation.party(observation))),
                        current.personnel(),
                        current.namespaceHash(),
                        resource,
                        resourceHash,
                        planJson,
                        planHash,
                        TaskStartOriginalReader.continuity(observation, binding, null),
                        null);
        String keyHash =
                IdempotencyScope.requestHash(
                        direct ? "BEAUTY_DIRECT_EXECUTION_KEY_V2" : "BEAUTY_EXECUTION_KEY_V1",
                        current.namespaceHash(),
                        request.requestKey());
        var command =
                new StartCommand(
                        direct ? "START_DIRECT_V2" : "START_MANUAL_V1",
                        requestHash(request, current, direct),
                        keyHash,
                        ServiceExecutionContract.bindingHash(binding),
                        intent,
                        action,
                        ServiceExecutionContract.actionHash(
                                binding,
                                "PREPARE",
                                action,
                                0,
                                current.namespaceHash(),
                                new StartIntentLookup(binding),
                                null));
        var row = new BtyServiceExecutionFence();
        row.setTenantNo("MAIN");
        row.setEntityNo(entity);
        row.setStoreNo(store);
        row.setFenceNo(fence);
        row.setExecutionNo(execution);
        row.setSubmissionNo(submission);
        row.setWorkOrderNo(order.getWorkOrderNo());
        row.setTaskNo(task.getTaskNo());
        row.setBindingNo(source.getBindingNo());
        row.setBindingRevision(source.getBindingRevision());
        row.setSourceKind("STORE_ORDER");
        row.setSourceNo(observation.getSourceNo());
        row.setOrderNo(observation.getOrderNo());
        row.setSourceOperationNo(observation.getCheckoutOperationNo());
        row.setSourceLineNo(source.getSourceLineRef());
        row.setUnitOrdinal(ordinal);
        row.setOrderedUnits(line.orderedQuantity());
        row.setQuantity(1);
        row.setUom("COUNT");
        row.setAttemptNo(1);
        row.setManualSourceRevision(binding.sourceRevision());
        row.setManualSourceHash(binding.sourceHash());
        row.setManualSourceEpoch(binding.sourceEpoch());
        row.setInitialTaskRevision(task.getVersion());
        row.setScopeRevision(order.getScopeRevision());
        row.setDefinitionVersionNo(task.getDefinitionVersionNo());
        row.setExecutionSnapshotHash(binding.executionSnapshotHash());
        row.setPlanHash(planHash);
        row.setResourceCommandNo(resource);
        row.setResourceCommandHash(resourceHash);
        row.setBindingHash(command.bindingHash());
        row.setPayloadHash(binding.payloadHash());
        row.setSourceIntentHash(ServiceExecutionContract.intentHash(intent));
        row.setBindingJson(ServiceExecutionContract.bindingJson(binding));
        row.setSourceFenceRevision(1L);
        row.setIntentState("FROZEN");
        row.setObservationEpoch(0L);
        row.setNextRetryAt(now());
        row.setVersion(0L);
        row.setDeleted(0);
        row.setCreatedAt(now());
        row.setUpdatedAt(now());
        row.setCreatedBy(current.identity().actorNo());
        row.setUpdatedBy(current.identity().actorNo());
        require(fences.insert(row) == 1);
        var start = new BtyServiceSubmission();
        start.setEntityNo(entity);
        start.setStoreNo(store);
        start.setSubmissionNo(submission);
        start.setWorkOrderNo(order.getWorkOrderNo());
        start.setAction("START");
        start.setRequestKeyHash(keyHash);
        start.setPayloadHash(binding.payloadHash());
        start.setStatus("EXECUTION_PENDING");
        start.setFrozenCommand(ServiceExecutionContract.canonical(command));
        start.setNextRetryAt(now());
        require(submissions.insert(start) == 1);
        return intent;
    }

    /**
     * HUMAN read rechecks live SELF and assignment; ORIGINAL read below never installs an audit
     * actor.
     */
    @Override
    public StartIntent inspect(StartIntentLookup request) {
        DelegatedIdentityContext.outside();
        var current = access.requireSelf(request.binding().scope());
        authorizeTask(request.binding(), current);
        return inspectOriginal(request);
    }

    @Override
    public StartIntent inspectOriginal(StartIntentLookup request) {
        DelegatedIdentityContext.outside();
        schema.requireAvailable();
        var expectedSchema = requireOriginalSchema(request.binding());
        return db.tx(
                () -> {
                    var locked = lock(request.binding(), expectedSchema);
                    return projection(locked.fence(), locked.command(), locked.observation());
                });
    }

    /**
     * Trusted local callback uses the existing mandatory source-to-fence lock ordering. No owner IO
     * is allowed.
     */
    public record ExecutionOriginal(
            BtyServiceExecutionFence fence,
            BtyServiceSubmission submission,
            StartCommand command,
            StartIntent currentIntent,
            BtyWorkOrder order,
            BtyServiceTask task,
            BtySourceObservation observation) {}

    <T> T withExecutionOriginal(ExecutionBinding binding, Function<ExecutionOriginal, T> action) {
        return originals.withExecutionOriginal(binding, action);
    }

    /**
     * Explicit HUMAN recovery may resend only a genuinely missing quantity original and the saved
     * command.
     */
    ExecutionPreparation resumeTask(StartIntentLookup request) {
        var current = access.requireSelf(request.binding().scope());
        authorizeTask(request.binding(), current);
        var claimed = claim(request, current.identity().actorNo());
        if (claimed == null)
            return new ExecutionPreparation(request.binding(), null, "SOURCE_SUSPENDED");
        var provider = owner.getIfAvailable();
        if (provider == null)
            return new ExecutionPreparation(
                    request.binding(), null, "RESOURCE_EXECUTION_UNAVAILABLE");
        Grant grant;
        try {
            grant = provider.queryOriginal(new ExecutionLookup(request.binding()));
        } catch (BizException missing) {
            if (missing.errorCode() != ErrorCode.NOT_FOUND) throw missing;
            access.requireSame(request.binding().scope(), current);
            var saved = claimed.command();
            grant =
                    provider.prepare(
                            new PrepareExecution(
                                    request.binding(),
                                    saved.actionNo(),
                                    saved.requestKeyHash(),
                                    saved.actionHash()));
        }
        access.requireSame(request.binding().scope(), current);
        return apply(claimed, grant, current.identity().actorNo());
    }

    private record Locked(
            BtySourceObservation observation,
            BtyServiceExecutionFence fence,
            BtyServiceSubmission start,
            BtyWorkOrder order,
            BtyServiceTask task,
            StartCommand command) {}

    /**
     * The scoped discriminator chooses its required schema gate before any canonical root locks.
     */
    private String requireOriginalSchema(ExecutionBinding binding) {
        return originals.requireOriginalSchema(binding);
    }

    private void requireDirectPlanSchema() {
        if (directPlans == null)
            throw BizException.of(
                    ErrorCode.BEAUTY_DEPENDENCY_UNAVAILABLE, "DIRECT_PLAN_SCHEMA_UNAVAILABLE");
        directPlans.requireAvailable();
    }

    private Locked lock(ExecutionBinding binding, String expectedSchema) {
        var o = originals.lock(binding, expectedSchema);
        return new Locked(
                o.observation(), o.fence(), o.submission(), o.order(), o.task(), o.command());
    }

    @Override
    public int recoverPending(int limit) {
        DelegatedIdentityContext.outside();
        if (limit < 1 || limit > 100) throw BizException.of(ErrorCode.BAD_REQUEST);
        if (schema.installation() == ServiceExecutionSchemaVerifier.Installation.ABSENT) return 0;
        schema.requireAvailable();
        var pending = db.read(() -> fences.pendingExecutions(now(), null, 0, limit));
        if (pending.isEmpty()) return 0;
        var provider = owner.getIfAvailable();
        if (provider == null) throw BizException.of(ErrorCode.BEAUTY_DEPENDENCY_UNAVAILABLE);
        int examined = 0;
        for (var candidate : pending) {
            var binding =
                    db.json.treeToValue(
                            db.json.readTree(candidate.getBindingJson()).get("binding"),
                            ExecutionBinding.class);
            require(
                    candidate.getBindingHash().equals(ServiceExecutionContract.bindingHash(binding))
                            && candidate
                                    .getBindingJson()
                                    .equals(ServiceExecutionContract.bindingJson(binding)));
            var expectedSchema = requireOriginalSchema(binding);
            Locked claimed =
                    db.tx(
                            () -> {
                                var locked = lock(binding, expectedSchema);
                                var row = locked.fence();
                                if (!row.getVersion().equals(candidate.getVersion())
                                        || "REJECTED".equals(row.getIntentState())
                                        || row.getNextRetryAt() != null
                                                && row.getNextRetryAt().isAfter(now())) return null;
                                require(
                                        fences.claimExecutionObservation(
                                                        row,
                                                        now().plusSeconds(30),
                                                        now(),
                                                        "EXECUTION_RECOVERY")
                                                == 1);
                                row.setVersion(Math.addExact(row.getVersion(), 1));
                                row.setObservationEpoch(
                                        Math.addExact(row.getObservationEpoch(), 1));
                                return locked;
                            });
            if (claimed == null) continue;
            try {
                var grant = provider.queryOriginal(new ExecutionLookup(binding));
                apply(claimed, grant, "EXECUTION_RECOVERY");
            } catch (RuntimeException unavailable) {
                // Lease and immutable original remain durable; no prepare/start/release or saved
                // identity replay.
            }
            if (stockObservation != null) {
                var stock = stockObservation.getIfAvailable();
                if (stock != null) {
                    try {
                        stock.queryAcceptedOriginal(binding);
                    } catch (RuntimeException unavailable) {
                        // Same lease already advanced; a damaged companion must not starve later
                        // quantity candidates.
                    }
                }
            }
            examined++;
        }
        return examined;
    }

    private Locked claim(StartIntentLookup request, String actor) {
        var expectedSchema = requireOriginalSchema(request.binding());
        return db.tx(
                () -> {
                    var locked = lock(request.binding(), expectedSchema);
                    var row = locked.fence();
                    if (row.getSuspensionIntentRevision() != null) return null;
                    require(
                            fences.claimExecutionObservation(
                                            row, now().plusSeconds(30), now(), actor)
                                    == 1);
                    row.setVersion(row.getVersion() + 1);
                    row.setObservationEpoch(row.getObservationEpoch() + 1);
                    return locked;
                });
    }

    private ExecutionPreparation apply(Locked claimed, Grant grant, String actor) {
        var binding = claimed.command().intent().binding();
        require(grant != null && binding.equals(grant.binding()));
        var expectedSchema = requireOriginalSchema(binding);
        return db.tx(
                () -> {
                    var locked = lock(binding, expectedSchema);
                    var row = locked.fence();
                    if (!row.getVersion().equals(claimed.fence().getVersion())
                            || !row.getObservationEpoch()
                                    .equals(claimed.fence().getObservationEpoch())
                            || !row.getSourceFenceRevision()
                                    .equals(claimed.fence().getSourceFenceRevision())) {
                        return new ExecutionPreparation(binding, null, "SOURCE_UNKNOWN");
                    }
                    require(
                            row.getObservedGrantNo() == null
                                    || row.getObservedGrantNo().equals(grant.grantNo()));
                    require(
                            row.getObservedGrantRevision() == null
                                    || row.getObservedGrantRevision() <= grant.grantRevision());
                    require(
                            grant.quantity().orderedUnits() == row.getOrderedUnits()
                                    && (grant.state() != ServiceExecutionPort.GrantState.PREPARED
                                            || grant.quantity().reservedUnits() > 0));
                    if (Objects.equals(row.getObservedGrantRevision(), grant.grantRevision())) {
                        require(Objects.equals(row.getObservedGrantState(), grant.state().name()));
                    }
                    row.setObservedGrantNo(grant.grantNo());
                    row.setObservedGrantRevision(grant.grantRevision());
                    row.setObservedGrantState(grant.state().name());
                    boolean prepared = grant.state() == ServiceExecutionPort.GrantState.PREPARED;
                    row.setIntentState(
                            row.getSuspensionIntentRevision() != null
                                    ? "SUSPEND_REQUESTED"
                                    : prepared ? "PREPARED_OBSERVED" : "EXECUTION_PENDING");
                    row.setNextRetryAt(now().plusSeconds(30));
                    row.setUpdatedAt(now());
                    row.setUpdatedBy(actor);
                    require(
                            fences.applyExecutionObservation(
                                            row,
                                            row.getVersion(),
                                            row.getSourceFenceRevision(),
                                            row.getObservationEpoch())
                                    == 1);
                    require(
                            submissions.completeExecutionOriginal(
                                            row.getEntityNo(),
                                            row.getStoreNo(),
                                            row.getSubmissionNo(),
                                            locked.start().getVersion(),
                                            row.getExecutionNo(),
                                            row.getBindingHash(),
                                            row.getPayloadHash(),
                                            row.getSuspensionIntentRevision() != null
                                                    ? "EXECUTION_SUSPENDED"
                                                    : prepared
                                                            ? "EXECUTION_PREPARED"
                                                            : "EXECUTION_PENDING",
                                            grant.reasonCode(),
                                            row.getNextRetryAt(),
                                            now(),
                                            actor)
                                    == 1);
                    if (List.of("SUSPEND_REQUESTED", "STARTED_SUSPENDED")
                            .contains(grant.state().name())) {
                        var gate =
                                submissions.taskStartForExecution(
                                        binding.scope().entityNo(),
                                        binding.scope().storeNo(),
                                        binding.executionNo());
                        if (gate != null) {
                            var frozen = TaskStartJournal.readFrozen(gate);
                            require(
                                    submissions.selectScopedForUpdate(
                                                    gate.getEntityNo(),
                                                    gate.getStoreNo(),
                                                    frozen.action().actionNo())
                                            != null);
                            gate =
                                    submissions.selectScopedForUpdate(
                                            gate.getEntityNo(),
                                            gate.getStoreNo(),
                                            gate.getSubmissionNo());
                            var observed = TaskStartJournal.readObservation(gate, frozen);
                            if (observed.phase() != TaskStartJournal.Phase.RESOURCE_SEALED)
                                TaskStartJournal.persist(
                                        submissions,
                                        gate,
                                        observed.next(
                                                null,
                                                null,
                                                null,
                                                null,
                                                "CORE_SUSPENSION_OBSERVATION_REQUIRED"),
                                        now(),
                                        now(),
                                        actor);
                        }
                    }
                    return new ExecutionPreparation(
                            binding,
                            grant,
                            row.getSuspensionIntentRevision() != null
                                    ? "SOURCE_SUSPENDED"
                                    : prepared ? null : "SOURCE_UNKNOWN");
                });
    }

    private StartIntent projection(
            BtyServiceExecutionFence row, StartCommand command, BtySourceObservation observation) {
        return TaskStartOriginalReader.projection(row, command, observation);
    }

    private Object plan(ServiceSubmissionQuery.Root root, BtyServiceTask task, Current current) {
        requireAssigned(task, current);
        if (root.order().getAppointmentNo() == null && task.getAppointmentItemNo() == null) {
            return DirectTaskPlan.ExecutionPlan.from(directPlan(task));
        }
        var original = root.original();
        var frozen = original.frozen();
        if (root.order().getAppointmentNo() == null
                || task.getAppointmentItemNo() == null
                || frozen == null
                || frozen.holdEvidence() == null) {
            throw BizException.of(
                    ErrorCode.CHECKOUT_UNSUPPORTED_SELECTION, "RESOURCE_PLAN_REQUIRED");
        }
        var evidence = frozen.holdEvidence();
        var holdBinding = evidence.binding();
        var appointment = db.root(root.order().getAppointmentNo());
        require(
                appointment != null
                        && root.order().getEntityNo().equals(appointment.getEntityNo())
                        && root.order().getStoreNo().equals(appointment.getStoreNo())
                        && "CHECKED_IN".equals(appointment.getStatus())
                        && appointment.getHoldNo().equals(evidence.hold().holdNo())
                        && "CONFIRMED".equals(evidence.hold().status())
                        && "MAIN".equals(holdBinding.tenantNo())
                        && root.order().getEntityNo().equals(holdBinding.entityNo())
                        && root.order().getStoreNo().equals(holdBinding.storeNo())
                        && appointment.getAppointmentNo().equals(holdBinding.sourceNo())
                        && "BEAUTY_APPOINTMENT".equals(holdBinding.sourceType())
                        && holdBinding.planHash().equals(evidence.hold().planHash())
                        && Objects.equals(
                                root.context().getAppointmentArrangementRevision(),
                                evidence.arrangementRevision()));
        var arrangement =
                db.arrangements.selectOne(
                        Wrappers.<BtyAppointmentArrangement>lambdaQuery()
                                .eq(BtyAppointmentArrangement::getTenantNo, "MAIN")
                                .eq(
                                        BtyAppointmentArrangement::getEntityNo,
                                        appointment.getEntityNo())
                                .eq(
                                        BtyAppointmentArrangement::getAppointmentNo,
                                        appointment.getAppointmentNo())
                                .eq(
                                        BtyAppointmentArrangement::getArrangementRevision,
                                        evidence.arrangementRevision()));
        require(
                arrangement != null
                        && arrangement.getHoldNo().equals(appointment.getHoldNo())
                        && evidence.arrangementHash()
                                .equals(
                                        IdempotencyScope.requestHash(
                                                arrangement.getPreviewNo(),
                                                arrangement.getHoldNo(),
                                                arrangement.getSnapshot())));
        var preview = db.preview(arrangement.getPreviewNo());
        require(
                preview.getSnapshot().equals(arrangement.getSnapshot())
                        && preview.getPlanHash().equals(holdBinding.planHash())
                        && ResourcePlanContract.hash(preview.getFrozenPlan())
                                .equals(holdBinding.planHash()));
        var operation =
                db.operations.selectOne(
                        Wrappers.<BtyAppointmentOperation>lambdaQuery()
                                .eq(BtyAppointmentOperation::getTenantNo, "MAIN")
                                .eq(BtyAppointmentOperation::getEntityNo, appointment.getEntityNo())
                                .eq(BtyAppointmentOperation::getStoreNo, appointment.getStoreNo())
                                .eq(
                                        BtyAppointmentOperation::getAppointmentNo,
                                        appointment.getAppointmentNo())
                                .eq(
                                        BtyAppointmentOperation::getPreviewNo,
                                        arrangement.getPreviewNo())
                                .eq(BtyAppointmentOperation::getState, "SUCCEEDED")
                                .in(
                                        BtyAppointmentOperation::getAction,
                                        "CREATE",
                                        "CHANGE",
                                        "RECEPTION_CHANGE"));
        require(operation != null && operation.getReceipt() != null);
        var receipt = db.json.readValue(operation.getReceipt(), ResourceBookingPort.Receipt.class);
        var accept =
                db.json.readValue(operation.getAcceptPayload(), ResourceBookingPort.Accept.class);
        require(
                "SUCCEEDED".equals(receipt.status())
                        && receipt.binding().equals(holdBinding)
                        && List.of("CONFIRM", "REPLACE").contains(holdBinding.action())
                        && receipt.frozenPayload().equals(preview.getFrozenPlan())
                        && accept.frozenPayload().equals(receipt.frozenPayload())
                        && accept.expectedOwnerVersion() == receipt.expectedOwnerVersion()
                        && Objects.equals(accept.target(), receipt.target())
                        && accept.sourceRevision() == holdBinding.sourceRevision()
                        && evidence.hold().sourceRevision() == holdBinding.sourceRevision());
        var result = db.json.readTree(receipt.resultPayload());
        require(
                evidence.hold().holdNo().equals(result.path("holdNo").asString())
                        && "CONFIRMED".equals(result.path("status").asString())
                        && evidence.hold().ownerVersion() == result.path("ownerRevision").asLong());
        var snapshot =
                db.json.readValue(arrangement.getSnapshot(), AppointmentMatching.Snapshot.class);
        var execution =
                db.json.readValue(
                        task.getExecutionSnapshot(),
                        WorkOrderCoordinator.TaskExecutionSnapshot.class);
        require(
                snapshot.assignments().stream()
                        .filter(a -> task.getAppointmentItemNo().equals(a.appointmentItemNo()))
                        .toList()
                        .equals(execution.assignments()));
        require(
                snapshot.items().equals(frozen.items())
                        && snapshot.assignments().equals(original.assignments())
                        && snapshot.items().size() == frozen.definitions().size());
        var persisted =
                db.assignments.selectByAppointmentRevision(
                        appointment.getEntityNo(),
                        appointment.getStoreNo(),
                        appointment.getAppointmentNo(),
                        evidence.arrangementRevision());
        require(persisted.size() == snapshot.assignments().size());
        for (var assignment : snapshot.assignments()) {
            require(
                    persisted.stream()
                            .anyMatch(
                                    a ->
                                            assignment
                                                            .appointmentItemNo()
                                                            .equals(a.getAppointmentItemNo())
                                                    && assignment
                                                            .requirementNo()
                                                            .equals(a.getRequirementNo())
                                                    && Objects.equals(
                                                            assignment.personnelNo(),
                                                            a.getPersonnelNo())
                                                    && assignment
                                                            .resourceNo()
                                                            .equals(a.getResourceNo())
                                                    && evidence.hold()
                                                            .holdNo()
                                                            .equals(a.getHoldNo())
                                                    && assignment
                                                            .startAt()
                                                            .toInstant()
                                                            .equals(
                                                                    a.getStartAt()
                                                                            .toInstant(
                                                                                    ZoneOffset.UTC))
                                                    && assignment
                                                            .endAt()
                                                            .toInstant()
                                                            .equals(
                                                                    a.getEndAt()
                                                                            .toInstant(
                                                                                    ZoneOffset
                                                                                            .UTC))));
        }
        // Reconstruct the full original grouping exactly as WorkOrderPreparation, including
        // resource ordinals.
        var reconstructed = new ArrayList<ResourcePlanContract.Demand>();
        var reconstructedQualifications = new ArrayList<ResourcePlanContract.Qualification>();
        for (int i = 0; i < snapshot.items().size(); i++) {
            var item = snapshot.items().get(i);
            var definition = frozen.definitions().get(i);
            require(
                    item.qty() == 1
                            && item.skuNo().equals(definition.skuNo())
                            && item.definitionVersionNo().equals(definition.definitionVersionNo()));
            for (var rule : definition.requirements()) {
                var assignments =
                        snapshot.assignments().stream()
                                .filter(
                                        a ->
                                                item.appointmentItemNo()
                                                                .equals(a.appointmentItemNo())
                                                        && rule.requirementNo()
                                                                .equals(a.requirementNo()))
                                .toList();
                require(assignments.size() == rule.qty());
                for (int ordinal = 0; ordinal < assignments.size(); ordinal++) {
                    var assignment = assignments.get(ordinal);
                    require(
                            assignment
                                            .startAt()
                                            .equals(
                                                    item.serviceStartAt()
                                                            .plusMinutes(rule.offsetMin())
                                                            .minusMinutes(
                                                                    definition.bufferBeforeMin()))
                                    && assignment
                                            .endAt()
                                            .equals(
                                                    item.serviceStartAt()
                                                            .plusMinutes(
                                                                    rule.offsetMin()
                                                                            + rule.durationMin()
                                                                            + definition
                                                                                    .bufferAfterMin()))
                                    && ("STAFF".equals(rule.resourceType())
                                            == (assignment.personnelNo() != null)));
                    String group =
                            item.appointmentItemNo() + ":" + rule.requirementNo() + ":" + ordinal;
                    reconstructed.add(
                            new ResourcePlanContract.Demand(
                                    assignment.resourceNo(),
                                    group,
                                    1,
                                    List.of(
                                            new ResourcePlanContract.Interval(
                                                    assignment.startAt().toInstant(),
                                                    assignment.endAt().toInstant()))));
                    reconstructedQualifications.add(
                            new ResourcePlanContract.Qualification(
                                    assignment.resourceNo(),
                                    group,
                                    assignment.personnelNo() == null ? null : rule.skillGoodsNo(),
                                    assignment.personnelNo() == null ? null : item.skuNo(),
                                    assignment.personnelNo() != null));
                }
            }
        }
        var all = ResourcePlanContract.decode(preview.getFrozenPlan());
        require(
                reconstructed.size() == snapshot.assignments().size()
                        && all.equals(
                                new ResourcePlanContract.FrozenPlan(
                                        reconstructed, reconstructedQualifications)));
        String prefix = task.getAppointmentItemNo() + ":";
        var demands =
                all.demands().stream()
                        .filter(d -> d.sourceLineRef().startsWith(prefix))
                        .sorted(
                                Comparator.comparing(ResourcePlanContract.Demand::sourceLineRef)
                                        .thenComparing(ResourcePlanContract.Demand::resourceNo))
                        .toList();
        var qualifications =
                all.qualifications().stream()
                        .filter(q -> q.sourceLineRef().startsWith(prefix))
                        .sorted(
                                Comparator.comparing(
                                                ResourcePlanContract.Qualification::sourceLineRef)
                                        .thenComparing(
                                                ResourcePlanContract.Qualification::resourceNo))
                        .toList();
        require(!demands.isEmpty() && demands.size() == execution.assignments().size());
        for (var demand : demands) {
            require(
                    demand.quantity() == 1
                            && demand.intervals().size() == 1
                            && execution.assignments().stream()
                                    .anyMatch(
                                            a ->
                                                    a.resourceNo().equals(demand.resourceNo())
                                                            && a.startAt()
                                                                    .toInstant()
                                                                    .equals(
                                                                            demand.intervals()
                                                                                    .getFirst()
                                                                                    .startAt())
                                                            && a.endAt()
                                                                    .toInstant()
                                                                    .equals(
                                                                            demand.intervals()
                                                                                    .getFirst()
                                                                                    .endAt())));
        }
        String taskPlan = ResourcePlanContract.encode(demands, qualifications);
        return new ExecutionPlan(
                "BEAUTY_EXECUTION_PLAN_V1",
                appointment.getAppointmentNo(),
                task.getAppointmentItemNo(),
                evidence.arrangementRevision(),
                evidence.arrangementHash(),
                evidence,
                preview.getFrozenPlan(),
                holdBinding.planHash(),
                IdempotencyScope.requestHash(task.getExecutionSnapshot()),
                taskPlan,
                ResourcePlanContract.hash(taskPlan));
    }

    private void authorizeTask(ExecutionBinding binding, Current current) {
        var root =
                query.root(
                        binding.scope().entityNo(),
                        binding.scope().storeNo(),
                        binding.workOrderNo());
        var task =
                root.tasks().stream()
                        .filter(t -> binding.taskNo().equals(t.getTaskNo()))
                        .findFirst()
                        .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND));
        requireAssigned(task, current);
    }

    private DirectTaskPlan directPlan(BtyServiceTask task) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            requireDirectPlanSchema();
        var root = query.root(task.getEntityNo(), currentStore(task), task.getWorkOrderNo());
        require(root.order().getAppointmentNo() == null && task.getAppointmentItemNo() == null);
        var fence =
                db.read(
                        () ->
                                fences.findTask(
                                        task.getEntityNo(),
                                        root.order().getStoreNo(),
                                        task.getTaskNo()));
        DirectTaskPlan plan;
        if (fence == null) {
            plan = ServiceTaskAssignmentServiceImpl.current(db, submissions, bindings, root, task);
        } else {
            var start =
                    db.read(
                            () ->
                                    submissions.selectSubmission(
                                            task.getEntityNo(),
                                            root.order().getStoreNo(),
                                            fence.getSubmissionNo()));
            var original = command(start);
            require("START_DIRECT_V2".equals(original.schema()));
            var frozen = DirectTaskPlan.ExecutionPlan.decode(original.intent().frozenPlan());
            plan = DirectTaskPlan.decode(frozen.directPlan());
            require(
                    plan.planNo().equals(task.getDirectPlanNo())
                            && plan.planRevision() == task.getDirectPlanRevision()
                            && plan.hash().equals(task.getDirectPlanHash()));
        }
        require(
                plan.taskNo().equals(task.getTaskNo())
                        && plan.workOrderNo().equals(task.getWorkOrderNo())
                        && plan.taskSnapshotHash()
                                .equals(IdempotencyScope.requestHash(task.getExecutionSnapshot())));
        return plan;
    }

    private String currentStore(BtyServiceTask task) {
        // Scope comes from the original OPEN submission, never a caller-provided store.
        var context =
                db.read(
                        () ->
                                submissions.selectOpenByWorkOrder(
                                        task.getEntityNo(), task.getWorkOrderNo()));
        require(context != null);
        return context.getStoreNo();
    }

    private void requireAssigned(BtyServiceTask task, Current current) {
        if (task.getAppointmentItemNo() == null) {
            var plan = directPlan(task);
            if (plan.assignments().stream()
                    .noneMatch(a -> current.personnel().personnelNo().equals(a.personnelNo()))) {
                throw BizException.of(ErrorCode.FORBIDDEN);
            }
            return;
        }
        var snapshot =
                db.json.readValue(
                        task.getExecutionSnapshot(),
                        WorkOrderCoordinator.TaskExecutionSnapshot.class);
        if (snapshot.schemaVersion() != 1)
            throw BizException.of(ErrorCode.CHECKOUT_UNSUPPORTED_SELECTION);
        if (!"ASSIGNED".equals(snapshot.assignmentState())
                || snapshot.assignments() == null
                || snapshot.assignments().isEmpty()) {
            throw BizException.of(
                    ErrorCode.CHECKOUT_UNSUPPORTED_SELECTION, "RESOURCE_PLAN_REQUIRED");
        }
        if (!"ASSIGNED".equals(snapshot.assignmentState())
                || snapshot.assignments() == null
                || snapshot.assignments().isEmpty()
                || snapshot.assignments().stream()
                        .noneMatch(
                                a ->
                                        task.getAppointmentItemNo() != null
                                                && task.getAppointmentItemNo()
                                                        .equals(a.appointmentItemNo())
                                                && current.personnel()
                                                        .personnelNo()
                                                        .equals(a.personnelNo()))) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
    }

    private static void requireTask(BtyServiceTask task) {
        try {
            ServiceExecutionContract.requireTaskQuantity(task.getQty(), task.getUom());
        } catch (IllegalArgumentException unsupported) {
            throw BizException.of(
                    ErrorCode.CHECKOUT_UNSUPPORTED_SELECTION, "UNSUPPORTED_TASK_QUANTITY");
        }
        require(
                "PENDING".equals(task.getStatus())
                        && task.getStartedAt() == null
                        && task.getDoneQty().signum() == 0
                        && task.getVoidedQty().signum() == 0);
    }

    private void requireParty(ServiceSubmissionQuery.Root root, BtySourceObservation observation) {
        var context = root.context();
        // Preserve original guest history even after a later claim; never substitute current
        // customer identity.
        if ("USER".equals(context.getIdentityKind())) {
            require(
                    "USER".equals(observation.getCustomerKind())
                            && context.getIdentityRef().equals(observation.getBuyerNo()));
        } else if ("GUEST".equals(observation.getCustomerKind())) {
            require(context.getIdentityRef().equals(observation.getGuestNo()));
        } else {
            var receipt =
                    claims.selectOne(
                            Wrappers.<BtyGuestClaimReceipt>lambdaQuery()
                                    .eq(BtyGuestClaimReceipt::getTenantNo, "MAIN")
                                    .eq(BtyGuestClaimReceipt::getEntityNo, context.getEntityNo())
                                    .eq(BtyGuestClaimReceipt::getStoreNo, context.getStoreNo())
                                    .eq(BtyGuestClaimReceipt::getGuestNo, context.getIdentityRef())
                                    .eq(BtyGuestClaimReceipt::getUserNo, observation.getBuyerNo())
                                    .eq(BtyGuestClaimReceipt::getClaimRevision, 2));
            require(
                    "USER".equals(observation.getCustomerKind())
                            && receipt != null
                            && receipt.getOwnerReceiptHash() != null
                            && receipt.getOwnerReceiptHash().matches("[0-9a-f]{64}"));
        }
    }

    private StartCommand command(BtyServiceSubmission row) {
        return TaskStartOriginalReader.command(db, row);
    }

    private static String directPlanHash(String bytes) {
        DirectTaskPlan.ExecutionPlan.decode(bytes);
        return IdempotencyScope.requestHash("BEAUTY_DIRECT_EXECUTION_PLAN_V2", bytes);
    }

    private static ExecutionBinding withPayload(ExecutionBinding b, String hash) {
        return new ExecutionBinding(
                b.scope(),
                b.caller(),
                b.sourceOperationNo(),
                b.orderNo(),
                b.subOrderNo(),
                b.sourceLineNo(),
                b.unitOrdinal(),
                b.workOrderNo(),
                b.scopeRevision(),
                b.taskNo(),
                b.taskRevision(),
                b.bindingNo(),
                b.bindingRevision(),
                b.executionNo(),
                b.attemptNo(),
                b.submissionNo(),
                b.sourceFenceNo(),
                b.sourceFenceRevision(),
                b.sourceEpoch(),
                b.sourceRevision(),
                b.sourceHash(),
                b.definitionVersionNo(),
                b.executionSnapshotHash(),
                b.planHash(),
                hash);
    }

    private static String planHash(String canonicalPlan) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            ("BEAUTY_EXECUTION_PLAN_V1\n" + canonicalPlan)
                                                    .getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
    }

    private String requestHash(FreezeStart request, Current current, boolean direct) {
        return IdempotencyScope.requestHash(
                direct ? "BEAUTY_DIRECT_EXECUTION_FREEZE_V2" : "BEAUTY_EXECUTION_FREEZE_V1",
                current.namespaceHash(),
                ServiceExecutionContract.canonical(request));
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static void require(boolean condition) {
        if (!condition) throw BizException.of(ErrorCode.CONFLICT);
    }
}
