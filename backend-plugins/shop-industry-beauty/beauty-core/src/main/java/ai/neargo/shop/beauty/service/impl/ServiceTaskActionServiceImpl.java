package ai.neargo.shop.beauty.service.impl;

import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.beauty.entity.BtyServiceSubmission;
import ai.neargo.shop.beauty.entity.BtyTradeBinding;
import ai.neargo.shop.beauty.mapper.BeautyMappers;
import ai.neargo.shop.beauty.policy.ServiceExecutionAccess;
import ai.neargo.shop.beauty.policy.ServiceExecutionAccess.Current;
import ai.neargo.shop.beauty.policy.TaskActionPolicy;
import ai.neargo.shop.beauty.policy.TaskActionSchemaVerifier;
import ai.neargo.shop.beauty.policy.TaskStartSchemaVerifier;
import ai.neargo.shop.beauty.service.ServiceTaskActionService;
import ai.neargo.shop.beauty.service.impl.ServiceTaskExecutionServiceImpl.ExecutionOriginal;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.idem.IdempotencyScope;
import ai.neargo.shop.spi.resource.ResourcePlanContract;
import ai.neargo.shop.spi.resource.ServiceTaskStartAuthorityPort.AcceptedStaffRef;
import ai.neargo.shop.spi.security.DelegatedIdentity;
import ai.neargo.shop.spi.trade.ExecutionEvidenceContract;
import ai.neargo.shop.spi.trade.ExecutionReference;
import ai.neargo.shop.spi.trade.ServiceExecutionContract;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionBinding;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionScope;
import ai.neargo.shop.spi.user.MerchantOperatorAccessPort;

import org.springframework.dao.DuplicateKeyException;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/** Immutable current-HUMAN intent only; no qualification, capacity, stock or task mutation. */
public final class ServiceTaskActionServiceImpl implements ServiceTaskActionService {
    private final AppointmentPersistence db;
    private final ServiceExecutionAccess access;
    private final TaskActionSchemaVerifier schema;
    private final ServiceTaskExecutionServiceImpl execution;
    private final ServiceTaskStockPreparationCoordinator stock;
    private final BeautyMappers.ServiceExecutionFenceMapper fences;
    private final BeautyMappers.TradeBindingMapper bindings;
    private final BeautyMappers.ServiceSubmissionMapper submissions;
    private final Clock clock;
    private final TaskStartOriginalReader originals;
    private final MerchantOperatorAccessPort operators;
    private final TaskStartSchemaVerifier startSchema;

    public ServiceTaskActionServiceImpl(
            AppointmentPersistence db,
            ServiceExecutionAccess access,
            TaskActionSchemaVerifier schema,
            ServiceTaskExecutionServiceImpl execution,
            ServiceTaskStockPreparationCoordinator stock,
            BeautyMappers.ServiceExecutionFenceMapper fences,
            BeautyMappers.TradeBindingMapper bindings,
            BeautyMappers.ServiceSubmissionMapper submissions,
            Clock clock,
            TaskStartOriginalReader originals,
            MerchantOperatorAccessPort operators,
            TaskStartSchemaVerifier startSchema) {
        this.originals = originals;
        this.operators = operators;
        this.startSchema = startSchema;
        this.db = db;
        this.access = access;
        this.schema = schema;
        this.execution = execution;
        this.stock = stock;
        this.fences = fences;
        this.bindings = bindings;
        this.submissions = submissions;
        this.clock = clock;
    }

    private record V2Capture(
            TaskActionVersions versions,
            TaskActionObservation observation,
            ExecutionReference reference,
            InitialPlan initial,
            List<AcceptedStaffRef> roster) {}

    @Override
    public TaskActionIntentV2 prepareStartActionV2(
            TaskActionContext context, StartActionCommandV2 command) {
        DelegatedIdentityContext.outside();
        startSchema.requireAvailable();
        originals.requireStockSchema();
        var current = access.requireSelf(context.scope());
        var binding = originals.locate(context);
        var choices =
                command.ownSlots().stream()
                        .sorted(
                                java.util.Comparator.comparing(AcceptedSlotSelection::requirementNo)
                                        .thenComparingInt(AcceptedSlotSelection::ordinal))
                        .toList();
        String key =
                IdempotencyScope.requestHash(
                        "BEAUTY_TASK_ACTION_KEY_V2",
                        ServiceExecutionContract.canonical(context),
                        current.namespaceHash(),
                        command.requestKey());
        String request =
                IdempotencyScope.requestHash(
                        "BEAUTY_TASK_ACTION_REQUEST_V2",
                        ServiceExecutionContract.canonical(context),
                        Long.toString(command.expectedScopeRevision()),
                        Long.toString(command.expectedTaskRevision()),
                        Long.toString(command.expectedBindingRevision()),
                        ServiceExecutionContract.canonical(choices));
        var prior =
                db.read(
                        () ->
                                submissions.selectActionKey(
                                        context.scope().entityNo(),
                                        context.scope().storeNo(),
                                        "TASK_ACTION",
                                        key));
        if (prior != null) return replayV2(prior, context, current, request);
        var captured =
                originals.withExecutionOriginal(
                        binding, o -> captureV2(o, command, current, choices));
        var canonical = canonical(current.identity(), context);
        var roster =
                captured.roster().stream()
                        .map(
                                r ->
                                        new AcceptedStaffRef(
                                                r.assignmentNo(),
                                                r.assignmentRevision(),
                                                r.requirementNo(),
                                                r.ordinal(),
                                                r.roleCode(),
                                                r.resourceNo(),
                                                r.personnelNo(),
                                                r.acceptActionNo(),
                                                r.recordHash(),
                                                r.qualificationHash(),
                                                r.identity(),
                                                canonical(r.identity(), context),
                                                r.personnel()))
                        .toList();
        require(
                roster.stream()
                        .filter(
                                r ->
                                        choices.stream()
                                                .anyMatch(
                                                        c ->
                                                                c.requirementNo()
                                                                                .equals(
                                                                                        r
                                                                                                .requirementNo())
                                                                        && c.ordinal()
                                                                                == r.ordinal()))
                        .allMatch(r -> canonical.equals(r.canonicalAccountNo())));
        access.requireSame(context.scope(), current);
        try {
            return originals.withExecutionOriginal(
                    binding,
                    o -> {
                        var winner =
                                submissions.selectActionKey(
                                        context.scope().entityNo(),
                                        context.scope().storeNo(),
                                        "TASK_ACTION",
                                        key);
                        if (winner != null) return replayV2(winner, context, current, request);
                        var after = captureV2(o, command, current, choices);
                        require(
                                captured.versions().equals(after.versions())
                                        && captured.reference().equals(after.reference())
                                        && captured.initial().equals(after.initial())
                                        && captured.roster().equals(after.roster()));
                        String target =
                                ExecutionEvidenceContract.hash(
                                        "BEAUTY_TASK_ACTION_TARGET_V2",
                                        List.of(
                                                context,
                                                after.versions(),
                                                after.reference(),
                                                after.initial(),
                                                choices,
                                                roster,
                                                current.personnel(),
                                                current.namespaceHash(),
                                                canonical));
                        if (submissions.selectTaskActionTarget(
                                        context.scope().entityNo(),
                                        context.scope().storeNo(),
                                        context.workOrderNo(),
                                        target)
                                != null)
                            throw BizException.of(
                                    ErrorCode.CONFLICT, "TASK_ACTION_ORIGINAL_REQUIRED");
                        var saved =
                                TaskActionV2Original.signed(
                                        new TaskActionIntentV2(
                                                "TASK_ACTION_V2",
                                                BizKey.next("BTSU"),
                                                Action.START_TASK,
                                                context,
                                                key,
                                                request,
                                                binding,
                                                after.reference(),
                                                choices,
                                                roster,
                                                after.initial(),
                                                after.versions(),
                                                after.observation(),
                                                current.identity(),
                                                current.personnel(),
                                                canonical,
                                                current.namespaceHash(),
                                                target,
                                                null));
                        var row = new BtyServiceSubmission();
                        row.setTenantNo(context.scope().tenantNo());
                        row.setEntityNo(context.scope().entityNo());
                        row.setStoreNo(context.scope().storeNo());
                        row.setWorkOrderNo(context.workOrderNo());
                        row.setSubmissionNo(saved.actionNo());
                        row.setAction("TASK_ACTION");
                        row.setStatus("TASK_INTENDED");
                        row.setRequestKeyHash(key);
                        row.setPayloadHash(saved.intentHash());
                        row.setFrozenCommand(ServiceExecutionContract.canonical(saved));
                        row.setVersion(0L);
                        row.setDeleted(0);
                        row.setCreatedAt(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
                        row.setUpdatedAt(row.getCreatedAt());
                        row.setCreatedBy(current.identity().actorNo());
                        row.setUpdatedBy(row.getCreatedBy());
                        require(submissions.insert(row) == 1);
                        return saved;
                    });
        } catch (DuplicateKeyException collision) {
            var winner =
                    db.read(
                            () ->
                                    submissions.selectActionKey(
                                            context.scope().entityNo(),
                                            context.scope().storeNo(),
                                            "TASK_ACTION",
                                            key));
            if (winner == null) throw collision;
            return replayV2(winner, context, current, request);
        }
    }

    private String canonical(DelegatedIdentity identity, TaskActionContext context) {
        var s = context.scope();
        return operators
                .resolveAuthorizedOperator(
                        new MerchantOperatorAccessPort.Query(
                                s.tenantNo(),
                                s.entityNo(),
                                s.storeNo(),
                                identity.actorNo(),
                                identity.realm(),
                                identity.subjectKind(),
                                "biz:service:task:self"))
                .orElseThrow(() -> BizException.of(ErrorCode.FORBIDDEN))
                .accountNo();
    }

    private V2Capture captureV2(
            ExecutionOriginal o,
            StartActionCommandV2 command,
            Current current,
            List<AcceptedSlotSelection> choices) {
        var f = originals.facts(o);
        var v = f.versions();
        require(
                v != null
                        && TaskStartOriginalReader.eligible(o, f.binding())
                        && v.scopeRevision() == command.expectedScopeRevision()
                        && v.taskVersion() == command.expectedTaskRevision()
                        && v.bindingRevision() == command.expectedBindingRevision());
        if (o.currentIntent().suspension() != null) unavailable("SOURCE_SUSPENDED");
        if (!"READY_UNCHANGED".equals(o.currentIntent().continuity().state()))
            unavailable("SOURCE_OBSERVATION_PENDING");
        if (!"PREPARED".equals(o.fence().getObservedGrantState())) unavailable("QUANTITY_PENDING");
        var roster = originals.acceptedRoster(o, clock.instant());
        TaskActionPolicy.requireOwnSelections(choices, roster, current.personnel());
        return new V2Capture(
                v,
                observation(o),
                originals.referenceForAction(o),
                FrozenTaskRolePlan.read(db, o).initial(),
                roster);
    }

    private TaskActionIntentV2 replayV2(
            BtyServiceSubmission row, TaskActionContext context, Current current, String request) {
        var saved = TaskActionV2Original.read(row);
        authorizeV2(saved, current);
        require(saved.context().equals(context) && saved.requestHash().equals(request));
        return saved;
    }

    private static void authorizeV2(TaskActionIntentV2 i, Current current) {
        if (!i.namespaceHash().equals(current.namespaceHash())
                || !i.actor().equals(current.identity())
                || !i.currentPersonnel().personnelNo().equals(current.personnel().personnelNo()))
            throw BizException.of(ErrorCode.FORBIDDEN);
    }

    @Override
    public TaskActionInspectionV2 inspectCurrentV2(TaskActionLookup lookup) {
        DelegatedIdentityContext.outside();
        startSchema.requireAvailable();
        originals.requireStockSchema();
        var current = access.requireSelf(lookup.scope());
        var saved =
                TaskActionV2Original.read(
                        db.read(
                                () ->
                                        submissions.selectSubmission(
                                                lookup.scope().entityNo(),
                                                lookup.scope().storeNo(),
                                                lookup.actionNo())));
        require(
                saved.context().scope().equals(lookup.scope())
                        && saved.intentHash().equals(lookup.intentHash()));
        authorizeV2(saved, current);
        return originals.withExecutionOriginal(
                saved.originalBinding(),
                o -> {
                    var row =
                            submissions.selectScopedForUpdate(
                                    lookup.scope().entityNo(),
                                    lookup.scope().storeNo(),
                                    lookup.actionNo());
                    require(saved.equals(TaskActionV2Original.read(row)));
                    var f = originals.facts(o);
                    var state = InspectionState.INTENDED_SLOT_CURRENT;
                    String reason = "CURRENT_OWNER_OBSERVATION_REQUIRED";
                    if (o.currentIntent().suspension() != null) {
                        state = InspectionState.SOURCE_SUSPENDED;
                        reason = "SOURCE_SUSPENDED";
                    } else if (!saved.expected().equals(f.versions())
                            || !saved.currentPersonnel().equals(current.personnel())) {
                        state = InspectionState.STALE;
                        reason = "TASK_ACTION_STALE";
                    } else if (!"READY_UNCHANGED".equals(o.currentIntent().continuity().state())) {
                        state = InspectionState.DEPENDENCY_PENDING;
                        reason = "SOURCE_OBSERVATION_PENDING";
                    } else {
                        var roster = originals.acceptedRoster(o, clock.instant());
                        require(stripAccounts(saved.acceptedRoster()).equals(roster));
                        TaskActionPolicy.requireOwnSelections(
                                saved.ownSlots(), roster, current.personnel());
                    }
                    return new TaskActionInspectionV2(
                            state, reason, saved, f.versions(), observation(o), clock.instant());
                });
    }

    static List<AcceptedStaffRef> stripAccounts(List<AcceptedStaffRef> roster) {
        return roster.stream()
                .map(
                        r ->
                                new AcceptedStaffRef(
                                        r.assignmentNo(),
                                        r.assignmentRevision(),
                                        r.requirementNo(),
                                        r.ordinal(),
                                        r.roleCode(),
                                        r.resourceNo(),
                                        r.personnelNo(),
                                        r.acceptActionNo(),
                                        r.recordHash(),
                                        r.qualificationHash(),
                                        r.identity(),
                                        null,
                                        r.personnel()))
                .toList();
    }

    @Override
    public TaskActionIntent prepareStartAction(
            TaskActionContext context, StartActionCommand command) {
        outsideSchema();
        var current = access.requireSelf(context.scope());
        var binding = locate(context);
        String key =
                IdempotencyScope.requestHash(
                        "BEAUTY_TASK_ACTION_KEY_V1",
                        ServiceExecutionContract.canonical(context),
                        Action.START_TASK.name(),
                        current.namespaceHash(),
                        command.requestKey());
        String request =
                IdempotencyScope.requestHash(
                        "BEAUTY_TASK_ACTION_REQUEST_V1",
                        ServiceExecutionContract.canonical(context),
                        Long.toString(command.expectedScopeRevision()),
                        Long.toString(command.expectedTaskRevision()),
                        Long.toString(command.expectedBindingRevision()));
        try {
            return execution.withExecutionOriginal(
                    binding,
                    original -> prepareLocked(context, command, current, original, key, request));
        } catch (DuplicateKeyException collision) {
            // Only a genuine unique-key winner can resolve this collision; no second insert/retry.
            return execution.withExecutionOriginal(
                    binding,
                    original -> {
                        var row =
                                submissions.selectActionKey(
                                        context.scope().entityNo(),
                                        context.scope().storeNo(),
                                        "TASK_ACTION",
                                        key);
                        if (row == null) throw collision;
                        return replay(row, context, current, request);
                    });
        }
    }

    private TaskActionIntent prepareLocked(
            TaskActionContext context,
            StartActionCommand command,
            Current current,
            ExecutionOriginal original,
            String key,
            String request) {
        // Replay verifies the stored immutable hash, not a new audit observation's hash.
        var prior =
                submissions.selectActionKey(
                        context.scope().entityNo(), context.scope().storeNo(), "TASK_ACTION", key);
        if (prior != null) return replay(prior, context, current, request);
        var facts = facts(original);
        var versions = facts.versions();
        require(
                versions != null
                        && versions.scopeRevision() == command.expectedScopeRevision()
                        && versions.taskVersion() == command.expectedTaskRevision()
                        && versions.bindingRevision() == command.expectedBindingRevision());
        require(eligible(original, facts.binding()));
        var intent = original.currentIntent();
        if (intent.suspension() != null) unavailable("SOURCE_SUSPENDED");
        if (!"READY_UNCHANGED".equals(intent.continuity().state())) unavailable("SOURCE_UNKNOWN");
        if (!"PREPARED".equals(original.fence().getObservedGrantState()))
            unavailable("QUANTITY_PENDING");
        var planned = planned(original);
        var slot = TaskActionPolicy.requirePlannedSelf(planned.slots(), current.personnel());
        TaskActionPolicy.resolveInitialSlot(
                slot, planned.definition(), ResourcePlanContract.decode(planned.taskPlan()));
        var reference = stock.referenceForAction(original);
        String target =
                ExecutionEvidenceContract.hash(
                        "BEAUTY_TASK_ACTION_TARGET_V1",
                        List.of(
                                context,
                                Action.START_TASK,
                                versions,
                                intent.binding(),
                                reference,
                                slot,
                                planned.initial(),
                                current.personnel(),
                                current.namespaceHash()));
        if (submissions.selectTaskActionTarget(
                        context.scope().entityNo(),
                        context.scope().storeNo(),
                        context.workOrderNo(),
                        target)
                != null) throw BizException.of(ErrorCode.CONFLICT, "TASK_ACTION_ORIGINAL_REQUIRED");
        var unsigned =
                new TaskActionIntent(
                        "TASK_ACTION_V1",
                        BizKey.next("BTSU"),
                        Action.START_TASK,
                        context,
                        key,
                        request,
                        intent.binding(),
                        reference,
                        slot,
                        planned.initial(),
                        versions,
                        observation(original),
                        current.identity(),
                        current.personnel(),
                        current.namespaceHash(),
                        target,
                        null);
        var saved = signed(unsigned);
        var row = new BtyServiceSubmission();
        row.setTenantNo(context.scope().tenantNo());
        row.setEntityNo(context.scope().entityNo());
        row.setStoreNo(context.scope().storeNo());
        row.setWorkOrderNo(context.workOrderNo());
        row.setSubmissionNo(saved.actionNo());
        row.setAction("TASK_ACTION");
        row.setStatus("TASK_INTENDED");
        row.setRequestKeyHash(key);
        row.setPayloadHash(saved.intentHash());
        row.setFrozenCommand(ServiceExecutionContract.canonical(saved));
        row.setVersion(0L);
        row.setDeleted(0);
        row.setNextRetryAt(null);
        row.setFailureCode(null);
        row.setCreatedAt(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
        row.setUpdatedAt(row.getCreatedAt());
        row.setCreatedBy(current.identity().actorNo());
        row.setUpdatedBy(current.identity().actorNo());
        require(submissions.insert(row) == 1);
        return saved;
    }

    @Override
    public TaskActionInspection inspectCurrent(TaskActionLookup lookup) {
        outsideSchema();
        var current = access.requireSelf(lookup.scope());
        var candidate =
                db.read(
                        () ->
                                submissions.selectSubmission(
                                        lookup.scope().entityNo(),
                                        lookup.scope().storeNo(),
                                        lookup.actionNo()));
        var saved = read(candidate);
        require(
                saved.context().scope().equals(lookup.scope())
                        && saved.intentHash().equals(lookup.intentHash()));
        return inspectSaved(saved, current);
    }

    private TaskActionInspection inspectSaved(TaskActionIntent saved, Current current) {
        authorize(saved, current);
        var scope = saved.context().scope();
        return execution.withExecutionOriginal(
                saved.originalBinding(),
                original -> {
                    // action is always locked after the canonical
                    // observation/guard/order/task/fence/START roots.
                    var row =
                            submissions.selectScopedForUpdate(
                                    scope.entityNo(), scope.storeNo(), saved.actionNo());
                    var intent = read(row);
                    require(intent.equals(saved));
                    return inspectLocked(intent, current, original);
                });
    }

    private TaskActionInspection inspectLocked(
            TaskActionIntent intent, Current current, ExecutionOriginal original) {
        var now = clock.instant();
        var facts = facts(original);
        var versions = facts.versions();
        var observation = observation(original);
        var source = original.currentIntent();
        InspectionState state;
        String reason;
        if (source.suspension() != null) {
            state = InspectionState.SOURCE_SUSPENDED;
            reason = "SOURCE_SUSPENDED";
        } else if (!eligible(original, facts.binding())
                || !intent.expected().equals(versions)
                || !intent.currentPersonnel().equals(current.personnel())) {
            state = InspectionState.STALE;
            reason = "TASK_ACTION_STALE";
        } else {
            var planned = planned(original);
            var slot = TaskActionPolicy.requirePlannedSelf(planned.slots(), current.personnel());
            TaskActionPolicy.resolveInitialSlot(
                    slot, planned.definition(), ResourcePlanContract.decode(planned.taskPlan()));
            if (!TaskActionPolicy.compareBusinessVersions(
                            intent, versions, current.personnel(), slot, planned.initial())
                    || !intent.reference().equals(stock.referenceForAction(original))) {
                state = InspectionState.STALE;
                reason = "TASK_ACTION_STALE";
            } else if (!"READY_UNCHANGED".equals(source.continuity().state())) {
                state = InspectionState.DEPENDENCY_PENDING;
                reason =
                        "SOURCE_OBSERVATION_IN_FLIGHT"
                                        .equals(original.observation().getManualFailureCode())
                                ? "SOURCE_OBSERVATION_IN_FLIGHT"
                                : "SOURCE_UNKNOWN";
            } else if (!"PREPARED".equals(original.fence().getObservedGrantState())) {
                state = InspectionState.DEPENDENCY_PENDING;
                reason = "QUANTITY_PENDING";
            } else {
                state = InspectionState.INTENDED_SLOT_CURRENT;
                reason = "QUALIFICATION_REQUIRED";
            }
        }
        return new TaskActionInspection(
                state, reason, intent, versions, observation, current.personnel(), now);
    }

    @Override
    public TaskActionView queryAction(TaskActionContext context, String actionNo) {
        return view(context, actionNo);
    }

    @Override
    public TaskActionView recoverAction(TaskActionContext context, String actionNo) {
        // There is no machine-accepted stage to continue in T02. This is an authenticated read.
        return view(context, actionNo);
    }

    private TaskActionView view(TaskActionContext context, String actionNo) {
        outsideSchema();
        var current = access.requireSelf(context.scope());
        var row =
                db.read(
                        () ->
                                submissions.selectSubmission(
                                        context.scope().entityNo(),
                                        context.scope().storeNo(),
                                        actionNo));
        var intent = read(row);
        require(context.equals(intent.context()));
        return safeView(inspectSaved(intent, current));
    }

    /**
     * Existing operation APIs carry actor separately; validate it against this request's context.
     */
    @Override
    public TaskActionView projectOperation(
            String entity, String store, String actor, String actionNo) {
        DelegatedIdentityContext.current(actor);
        outsideSchema();
        var current = access.requireSelf(new ExecutionScope("MAIN", entity, store));
        var row = db.read(() -> submissions.selectSubmission(entity, store, actionNo));
        if (row != null
                && "TASK_ACTION_V2"
                        .equals(
                                db.json
                                        .readTree(row.getFrozenCommand())
                                        .path("schema")
                                        .asString())) {
            var v2 = TaskActionV2Original.read(row);
            var inspected =
                    inspectCurrentV2(
                            new TaskActionLookup(v2.context().scope(), actionNo, v2.intentHash()));
            return new TaskActionView(
                    actionNo,
                    v2.context().workOrderNo(),
                    v2.context().taskNo(),
                    v2.purpose(),
                    inspected.state(),
                    inspected.reason());
        }
        var intent = read(row);
        require(
                entity.equals(intent.context().scope().entityNo())
                        && store.equals(intent.context().scope().storeNo()));
        return safeView(inspectSaved(intent, current));
    }

    private static TaskActionView safeView(TaskActionInspection inspected) {
        var intent = inspected.intent();
        var context = intent.context();
        return new TaskActionView(
                intent.actionNo(),
                context.workOrderNo(),
                context.taskNo(),
                intent.purpose(),
                inspected.state(),
                inspected.reason());
    }

    private void outsideSchema() {
        DelegatedIdentityContext.outside();
        schema.requireAvailable();
        stock.requireActionReferenceSchema();
    }

    private ExecutionBinding locate(TaskActionContext context) {
        var row =
                db.read(
                        () ->
                                fences.findTask(
                                        context.scope().entityNo(),
                                        context.scope().storeNo(),
                                        context.taskNo()));
        if (row == null) {
            unavailable("EXECUTION_ORIGINAL_REQUIRED");
        }
        var binding =
                db.json.treeToValue(
                        db.json.readTree(row.getBindingJson()).get("binding"),
                        ExecutionBinding.class);
        require(
                binding.scope().equals(context.scope())
                        && binding.workOrderNo().equals(context.workOrderNo())
                        && binding.taskNo().equals(context.taskNo()));
        return binding;
    }

    private TaskActionIntent replay(
            BtyServiceSubmission row, TaskActionContext context, Current current, String request) {
        var intent = read(row);
        authorize(intent, current);
        require(intent.context().equals(context) && intent.requestHash().equals(request));
        return intent;
    }

    private void authorize(TaskActionIntent intent, Current current) {
        if (!intent.namespaceHash().equals(current.namespaceHash())
                || !intent.actor().equals(current.identity())
                || !intent.currentPersonnel()
                        .personnelNo()
                        .equals(current.personnel().personnelNo())) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
    }

    private TaskActionIntent read(BtyServiceSubmission row) {
        if (row == null) throw BizException.of(ErrorCode.NOT_FOUND);
        require("TASK_ACTION".equals(row.getAction()) && "TASK_INTENDED".equals(row.getStatus()));
        var intent = db.json.readValue(row.getFrozenCommand(), TaskActionIntent.class);
        require(
                "TASK_ACTION_V1".equals(intent.schema())
                        && intent.purpose() == Action.START_TASK
                        && row.getSubmissionNo().equals(intent.actionNo())
                        && row.getPayloadHash().equals(intent.intentHash())
                        && row.getRequestKeyHash().equals(intent.requestKeyHash())
                        && row.getWorkOrderNo().equals(intent.context().workOrderNo())
                        && row.getTenantNo().equals(intent.context().scope().tenantNo())
                        && row.getEntityNo().equals(intent.context().scope().entityNo())
                        && row.getStoreNo().equals(intent.context().scope().storeNo())
                        && row.getNextRetryAt() == null
                        && row.getFailureCode() == null
                        && row.getVersion() == 0
                        && row.getDeleted() == 0
                        && intent.equals(signed(intent))
                        && db.json
                                .readTree(row.getFrozenCommand())
                                .equals(
                                        db.json.readTree(
                                                ServiceExecutionContract.canonical(intent))));
        intent.reference().validate();
        require(
                intent.reference()
                        .bindingHash()
                        .equals(ServiceExecutionContract.bindingHash(intent.originalBinding())));
        return intent;
    }

    TaskStartOriginalReader.Facts facts(ExecutionOriginal original) {
        return originals.facts(original);
    }

    boolean eligible(ExecutionOriginal original, BtyTradeBinding binding) {
        return TaskStartOriginalReader.eligible(original, binding);
    }

    static TaskActionObservation observation(ExecutionOriginal o) {
        return new TaskActionObservation(
                o.order().getVersion(),
                o.observation().getManualEpoch(),
                o.observation().getVersion());
    }

    private static TaskActionIntent signed(TaskActionIntent i) {
        return new TaskActionIntent(
                i.schema(),
                i.actionNo(),
                i.purpose(),
                i.context(),
                i.requestKeyHash(),
                i.requestHash(),
                i.originalBinding(),
                i.reference(),
                i.slot(),
                i.initialPlan(),
                i.expected(),
                i.observedAtPreparation(),
                i.actor(),
                i.currentPersonnel(),
                i.namespaceHash(),
                i.targetHash(),
                ExecutionEvidenceContract.digest("BEAUTY_TASK_ACTION_INTENT_V1", i, "intentHash"));
    }

    FrozenTaskRolePlan.Planned planned(ExecutionOriginal original) {
        return FrozenTaskRolePlan.read(db, original);
    }

    private static void unavailable(String reason) {
        throw BizException.of(ErrorCode.BEAUTY_DEPENDENCY_UNAVAILABLE, reason);
    }

    private static void require(boolean valid) {
        if (!valid) throw BizException.of(ErrorCode.CONFLICT);
    }
}
