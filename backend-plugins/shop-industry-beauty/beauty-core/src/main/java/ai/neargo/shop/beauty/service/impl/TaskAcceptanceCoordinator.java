package ai.neargo.shop.beauty.service.impl;

import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.beauty.dto.AcceptTaskReq;
import ai.neargo.shop.beauty.dto.AcceptedTaskSlotVO;
import ai.neargo.shop.beauty.dto.OperationVO;
import ai.neargo.shop.beauty.dto.TaskActionResultVO;
import ai.neargo.shop.beauty.entity.BtyServiceSubmission;
import ai.neargo.shop.beauty.entity.BtyTaskAssignmentSlot;
import ai.neargo.shop.beauty.mapper.BeautyMappers;
import ai.neargo.shop.beauty.policy.ServiceExecutionAccess;
import ai.neargo.shop.beauty.policy.ServiceExecutionAccess.Current;
import ai.neargo.shop.beauty.policy.TaskAcceptanceSchemaVerifier;
import ai.neargo.shop.beauty.policy.TaskActionPolicy;
import ai.neargo.shop.beauty.service.ServiceTaskActionService.InitialPlan;
import ai.neargo.shop.beauty.service.ServiceTaskActionService.PlannedRoleSlot;
import ai.neargo.shop.beauty.service.ServiceTaskActionService.TaskActionContext;
import ai.neargo.shop.beauty.service.ServiceTaskActionService.TaskActionObservation;
import ai.neargo.shop.beauty.service.ServiceTaskActionService.TaskActionVersions;
import ai.neargo.shop.beauty.service.impl.ServiceTaskExecutionServiceImpl.ExecutionOriginal;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.idem.IdempotencyScope;
import ai.neargo.shop.spi.personnel.PersonnelOperatorFactsPort.OperatorPersonnelFact;
import ai.neargo.shop.spi.resource.ResourcePlanContract;
import ai.neargo.shop.spi.resource.ResourceQualificationPort;
import ai.neargo.shop.spi.resource.ResourceQualificationPort.CoveredBucket;
import ai.neargo.shop.spi.resource.ResourceQualificationPort.Observation;
import ai.neargo.shop.spi.resource.ResourceQualificationPort.QualifiedGroup;
import ai.neargo.shop.spi.security.DelegatedIdentity;
import ai.neargo.shop.spi.trade.ExecutionEvidenceContract;
import ai.neargo.shop.spi.trade.ExecutionReference;
import ai.neargo.shop.spi.trade.ServiceExecutionContract;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionBinding;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionScope;

import org.springframework.dao.DuplicateKeyException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Pure owner IO separates two short local snapshots. Only the final transaction publishes immutable
 * acceptance and all selected pointers; no machine intent, physical effect or capacity claim is
 * created.
 */
public final class TaskAcceptanceCoordinator {
    private final AppointmentPersistence db;
    private final ServiceExecutionAccess access;
    private final TaskAcceptanceSchemaVerifier schema;
    private final ServiceTaskExecutionServiceImpl execution;
    private final ServiceTaskActionServiceImpl actions;
    private final ServiceTaskStockPreparationCoordinator stock;
    private final BeautyMappers.ServiceExecutionFenceMapper fences;
    private final BeautyMappers.ServiceSubmissionMapper submissions;
    private final BeautyMappers.ServiceTaskMapper tasks;
    private final BeautyMappers.TaskAssignmentSlotMapper slots;
    private final ResourceQualificationPort qualification;
    private final Clock clock;

    public TaskAcceptanceCoordinator(
            AppointmentPersistence db,
            ServiceExecutionAccess access,
            TaskAcceptanceSchemaVerifier schema,
            ServiceTaskExecutionServiceImpl execution,
            ServiceTaskActionServiceImpl actions,
            ServiceTaskStockPreparationCoordinator stock,
            BeautyMappers.ServiceExecutionFenceMapper fences,
            BeautyMappers.ServiceSubmissionMapper submissions,
            BeautyMappers.ServiceTaskMapper tasks,
            BeautyMappers.TaskAssignmentSlotMapper slots,
            ResourceQualificationPort qualification,
            Clock clock) {
        this.db = db;
        this.access = access;
        this.schema = schema;
        this.execution = execution;
        this.actions = actions;
        this.stock = stock;
        this.fences = fences;
        this.submissions = submissions;
        this.tasks = tasks;
        this.slots = slots;
        this.qualification = qualification;
        this.clock = clock;
    }

    /**
     * Actual current actor B may accept their role even when actor A prepared the saved B3
     * original.
     */
    public TaskActionResultVO acceptTask(
            String entity,
            String store,
            String actor,
            String taskNo,
            AcceptTaskReq request,
            String key) {
        outside();
        DelegatedIdentityContext.current(actor);
        var scope = new ExecutionScope("MAIN", entity, store);
        var current = access.requireSelf(scope);
        var binding = locate(scope, taskNo);
        var context = new TaskActionContext(scope, binding.workOrderNo(), taskNo);
        return acceptTask(context, request, key, current, binding);
    }

    private TaskActionResultVO acceptTask(
            TaskActionContext context,
            AcceptTaskReq request,
            String key,
            Current current,
            ExecutionBinding binding) {
        String keyHash =
                IdempotencyScope.requestHash(
                        "BEAUTY_TASK_ACCEPT_KEY_V1",
                        ServiceExecutionContract.canonical(context),
                        current.namespaceHash(),
                        key);
        String requestHash =
                IdempotencyScope.requestHash(
                        "BEAUTY_TASK_ACCEPT_REQUEST_V1",
                        ServiceExecutionContract.canonical(context),
                        ServiceExecutionContract.canonical(request));
        var prior =
                db.read(
                        () ->
                                submissions.selectActionKey(
                                        context.scope().entityNo(),
                                        context.scope().storeNo(),
                                        "TASK_ACCEPT",
                                        keyHash));
        // Permission is current, but exact replay never asks for a fresh qualification or publishes
        // a revision.
        if (prior != null) {
            return result(replay(prior, context, current, requestHash), null);
        }
        var prepared =
                execution.withExecutionOriginal(
                        binding,
                        original -> {
                            var winner =
                                    submissions.selectActionKey(
                                            context.scope().entityNo(),
                                            context.scope().storeNo(),
                                            "TASK_ACCEPT",
                                            keyHash);
                            if (winner != null) {
                                return new Prepared(
                                        null, replay(winner, context, current, requestHash));
                            }
                            return new Prepared(capture(context, request, current, original), null);
                        });
        if (prepared.original() != null) {
            return result(prepared.original(), null);
        }
        var captured = prepared.capture();
        var observed = qualification.observe(captured.query(requestHash));
        validateObservation(
                captured.query(requestHash), observed, current.personnel(), clock.instant());
        access.requireSame(context.scope(), current);
        try {
            return execution.withExecutionOriginal(
                    binding,
                    original -> {
                        var winner =
                                submissions.selectActionKey(
                                        context.scope().entityNo(),
                                        context.scope().storeNo(),
                                        "TASK_ACCEPT",
                                        keyHash);
                        if (winner != null) {
                            return result(replay(winner, context, current, requestHash), null);
                        }
                        var after = capture(context, request, current, original);
                        require(captured.equals(after));
                        validateObservation(
                                after.query(requestHash),
                                observed,
                                current.personnel(),
                                clock.instant());
                        return publish(
                                context,
                                current,
                                original,
                                captured,
                                observed,
                                keyHash,
                                requestHash);
                    });
        } catch (DuplicateKeyException collision) {
            var winner =
                    db.read(
                            () ->
                                    submissions.selectActionKey(
                                            context.scope().entityNo(),
                                            context.scope().storeNo(),
                                            "TASK_ACCEPT",
                                            keyHash));
            if (winner == null) {
                throw collision;
            }
            return result(replay(winner, context, current, requestHash), null);
        }
    }

    private Capture capture(
            TaskActionContext context,
            AcceptTaskReq request,
            Current current,
            ExecutionOriginal original) {
        require(
                context.scope().equals(original.command().intent().binding().scope())
                        && context.taskNo().equals(original.task().getTaskNo())
                        && context.workOrderNo().equals(original.order().getWorkOrderNo()));
        require(
                submissions.protectedTaskStarts(
                                context.scope().entityNo(),
                                context.scope().storeNo(),
                                context.taskNo())
                        == 0);
        var facts = actions.facts(original);
        require(actions.eligible(original, facts.binding()));
        require(
                facts.versions().taskVersion() == request.expectedRevision()
                        && facts.versions().bindingRevision() == request.expectedBindingRevision()
                        && facts.versions().scopeRevision() == request.expectedScopeRevision());
        requireSource(original);
        var planned = actions.planned(original);
        var fullPlan = ResourcePlanContract.decode(planned.taskPlan());
        var selected = new ArrayList<SelectedSlot>();
        var seen = new HashSet<String>();
        for (var choice : request.slots()) {
            require(seen.add(choice.requirementNo() + ":" + choice.ordinal()));
            var matching =
                    planned.slots().stream()
                            .filter(
                                    s ->
                                            s.requirementNo().equals(choice.requirementNo())
                                                    && s.ordinal() == choice.ordinal())
                            .toList();
            require(matching.size() == 1);
            var slot = matching.getFirst();
            if (!current.personnel().personnelNo().equals(slot.personnelNo())) {
                throw BizException.of(ErrorCode.FORBIDDEN);
            }
            TaskActionPolicy.resolveInitialSlot(slot, planned.definition(), fullPlan);
            selected.add(new SelectedSlot(slot, choice.expectedAssignmentRevision()));
        }
        require(!selected.isEmpty());
        selected.sort(
                Comparator.comparing((SelectedSlot s) -> s.slot().requirementNo())
                        .thenComparingInt(s -> s.slot().ordinal()));
        var reference = stock.referenceForAction(original);
        var pointers = new ArrayList<Pointer>();
        for (var selection : selected) {
            var row =
                    slots.lockSlot(
                            context.scope().entityNo(),
                            context.scope().storeNo(),
                            context.taskNo(),
                            selection.slot().requirementNo(),
                            selection.slot().ordinal());
            long revision = row == null ? 0 : row.getAssignmentRevision();
            require(revision == selection.expectedAssignmentRevision());
            pointers.add(
                    row == null
                            ? new Pointer(null, 0, 0, null)
                            : new Pointer(
                                    row.getAssignmentNo(),
                                    row.getAssignmentRevision(),
                                    row.getVersion(),
                                    row.getAcceptActionNo()));
        }
        var groups =
                selected.stream().map(s -> s.slot().sourceLineRef()).collect(Collectors.toSet());
        var demands =
                fullPlan.demands().stream()
                        .filter(d -> groups.contains(d.sourceLineRef()))
                        .toList();
        var requirements =
                fullPlan.qualifications().stream()
                        .filter(q -> groups.contains(q.sourceLineRef()))
                        .toList();
        require(
                demands.size() == selected.size()
                        && requirements.size() == selected.size()
                        && requirements.stream()
                                .allMatch(ResourcePlanContract.Qualification::required));
        String rosterHash =
                ExecutionEvidenceContract.hash("BEAUTY_REQUIRED_STAFF_V1", planned.slots());
        var continuous =
                new AcceptanceBinding(
                        original.task().getScopeRevision(),
                        facts.versions().bindingVersion(),
                        facts.versions().sourceFenceRevision(),
                        planned.initial(),
                        rosterHash,
                        reference.referenceHash());
        return new Capture(
                facts.versions(),
                continuous,
                reference,
                List.copyOf(selected),
                List.copyOf(pointers),
                demands,
                requirements);
    }

    private TaskActionResultVO publish(
            TaskActionContext context,
            Current current,
            ExecutionOriginal original,
            Capture captured,
            Observation observed,
            String keyHash,
            String requestHash) {
        var at = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        String actionNo = BizKey.next("BTSU");
        var results = new ArrayList<AcceptedTaskSlotVO>();
        for (int i = 0; i < captured.selected().size(); i++) {
            var selected = captured.selected().get(i);
            var pointer = captured.pointers().get(i);
            if (pointer.actionNo() != null) {
                var old =
                        read(
                                submissions.selectSubmission(
                                        context.scope().entityNo(),
                                        context.scope().storeNo(),
                                        pointer.actionNo()));
                var oldSelection =
                        old.selected().stream()
                                .filter(s -> sameSlot(s.slot(), selected.slot()))
                                .findFirst()
                                .orElseThrow();
                require(oldSelection.slot().equals(selected.slot()));
                if (old.acceptanceBinding().equals(captured.binding())
                        && old.currentPersonnel().equals(current.personnel())
                        && sameOwnerGeneration(
                                group(old.qualification(), selected.slot()),
                                group(observed, selected.slot()))
                        && at.isBefore(
                                groupValidUntil(group(old.qualification(), selected.slot())))) {
                    throw BizException.of(ErrorCode.CONFLICT, "TASK_SLOT_ALREADY_ACCEPTED");
                }
            }
            results.add(
                    new AcceptedTaskSlotVO(
                            pointer.assignmentNo() == null
                                    ? BizKey.next("BTAS")
                                    : pointer.assignmentNo(),
                            selected.slot().requirementNo(),
                            selected.slot().ordinal(),
                            selected.slot().roleCode(),
                            Math.addExact(pointer.assignmentRevision(), 1),
                            groupValidUntil(group(observed, selected.slot())),
                            "CURRENT_OBSERVATION_REQUIRED"));
        }
        var unsigned =
                new TaskAcceptanceRecord(
                        "TASK_ACCEPT_V1",
                        "ACCEPT_TASK",
                        actionNo,
                        context,
                        keyHash,
                        requestHash,
                        current.namespaceHash(),
                        current.identity(),
                        current.personnel(),
                        original.command().intent().binding(),
                        captured.reference(),
                        captured.binding().plan(),
                        captured.binding().requiredRosterHash(),
                        captured.selected(),
                        captured.versions(),
                        captured.binding(),
                        ServiceTaskActionServiceImpl.observation(original),
                        observed,
                        at,
                        Math.addExact(captured.versions().taskVersion(), 1),
                        results,
                        null);
        var record = signed(unsigned);
        var row = new BtyServiceSubmission();
        row.setTenantNo(context.scope().tenantNo());
        row.setEntityNo(context.scope().entityNo());
        row.setStoreNo(context.scope().storeNo());
        row.setWorkOrderNo(context.workOrderNo());
        row.setSubmissionNo(actionNo);
        row.setAction("TASK_ACCEPT");
        row.setStatus("TASK_ACCEPTED");
        row.setRequestKeyHash(keyHash);
        row.setPayloadHash(record.recordHash());
        row.setFrozenCommand(ServiceExecutionContract.canonical(record));
        audit(row, current.identity().actorNo(), at);
        require(submissions.insert(row) == 1);
        for (int i = 0; i < captured.selected().size(); i++) {
            var pointer = captured.pointers().get(i);
            var slot = captured.selected().get(i).slot();
            var accepted = results.get(i);
            var projection = new BtyTaskAssignmentSlot();
            projection.setTenantNo(context.scope().tenantNo());
            projection.setEntityNo(context.scope().entityNo());
            projection.setStoreNo(context.scope().storeNo());
            projection.setWorkOrderNo(context.workOrderNo());
            projection.setTaskNo(context.taskNo());
            projection.setAssignmentNo(accepted.assignmentNo());
            projection.setRequirementNo(slot.requirementNo());
            projection.setRoleOrdinal(slot.ordinal());
            projection.setRoleCode(slot.roleCode());
            projection.setPersonnelNo(slot.personnelNo());
            projection.setResourceNo(slot.resourceNo());
            projection.setPlanKind(record.initialPlan().kind().name());
            projection.setPlanNo(record.initialPlan().planNo());
            projection.setPlanRevision(record.initialPlan().planRevision());
            projection.setPlanHash(record.initialPlan().planHash());
            projection.setReferenceHash(record.reference().referenceHash());
            projection.setAcceptActionNo(actionNo);
            projection.setAcceptRecordHash(record.recordHash());
            projection.setQualificationHash(observed.observationHash());
            projection.setAssignmentRevision(accepted.assignmentRevision());
            projection.setAcceptedAt(local(at));
            projection.setValidUntil(local(accepted.validUntil()));
            projection.setVersion(0L);
            projection.setDeleted(0);
            projection.setCreatedAt(local(at));
            projection.setUpdatedAt(local(at));
            projection.setCreatedBy(current.identity().actorNo());
            projection.setUpdatedBy(current.identity().actorNo());
            require(
                    pointer.assignmentNo() == null
                            ? slots.insert(projection) == 1
                            : slots.replaceAcceptance(
                                            projection,
                                            pointer.rowVersion(),
                                            pointer.assignmentRevision())
                                    == 1);
        }
        require(
                tasks.advanceAcceptanceRevision(
                                context.scope().entityNo(),
                                context.scope().storeNo(),
                                context.taskNo(),
                                captured.versions().taskVersion(),
                                current.identity().actorNo(),
                                local(at))
                        == 1);
        return result(record, null);
    }

    /** Original-number recovery is an authorized read; it never retries a saved HUMAN command. */
    public TaskActionResultVO queryOriginal(TaskActionContext context, String actionNo) {
        outside();
        var current = access.requireSelf(context.scope());
        var record =
                read(
                        db.read(
                                () ->
                                        submissions.selectSubmission(
                                                context.scope().entityNo(),
                                                context.scope().storeNo(),
                                                actionNo)));
        require(record.context().equals(context));
        authorize(record, current);
        return execution.withExecutionOriginal(
                record.originalBinding(),
                original -> {
                    var facts = actions.facts(original);
                    String reason = "CURRENT_OBSERVATION_REQUIRED";
                    if (original.currentIntent().suspension() != null) {
                        reason = "SOURCE_SUSPENDED";
                    } else if (!actions.eligible(original, facts.binding())
                            || facts.versions() == null
                            || facts.versions().bindingVersion()
                                    != record.acceptanceBinding().bindingVersion()
                            || facts.versions().sourceFenceRevision()
                                    != record.acceptanceBinding().sourceFenceRevision()
                            || !record.currentPersonnel().equals(current.personnel())) {
                        reason = "NOT_CURRENT";
                    } else if (!"READY_UNCHANGED"
                            .equals(original.currentIntent().continuity().state())) {
                        reason = "SOURCE_OBSERVATION_PENDING";
                    } else {
                        var plan = actions.planned(original);
                        if (!plan.initial().equals(record.initialPlan())
                                || !record.requiredRosterHash()
                                        .equals(
                                                ExecutionEvidenceContract.hash(
                                                        "BEAUTY_REQUIRED_STAFF_V1",
                                                        plan.slots()))) {
                            reason = "NOT_CURRENT";
                        }
                    }
                    var projected = new ArrayList<AcceptedTaskSlotVO>();
                    for (var saved : record.result()) {
                        var pointer =
                                slots.lockSlot(
                                        context.scope().entityNo(),
                                        context.scope().storeNo(),
                                        context.taskNo(),
                                        saved.requirementNo(),
                                        saved.ordinal());
                        String state = reason;
                        if (pointer == null
                                || !record.actionNo().equals(pointer.getAcceptActionNo())
                                || saved.assignmentRevision() != pointer.getAssignmentRevision()
                                || !record.recordHash().equals(pointer.getAcceptRecordHash())
                                || !clock.instant().isBefore(saved.validUntil())) {
                            state = "NOT_CURRENT";
                        }
                        projected.add(
                                new AcceptedTaskSlotVO(
                                        saved.assignmentNo(),
                                        saved.requirementNo(),
                                        saved.ordinal(),
                                        saved.roleCode(),
                                        saved.assignmentRevision(),
                                        saved.validUntil(),
                                        state));
                    }
                    return new TaskActionResultVO(
                            record.actionNo(),
                            context.taskNo(),
                            "ACCEPT_TASK",
                            "ACCEPTED",
                            record.acceptedAt(),
                            projected,
                            reason,
                            false);
                });
    }

    public OperationVO projectOperation(
            String entity, String store, String actor, String actionNo) {
        outside();
        DelegatedIdentityContext.current(actor);
        var record = read(db.read(() -> submissions.selectSubmission(entity, store, actionNo)));
        require(record.context().scope().equals(new ExecutionScope("MAIN", entity, store)));
        var view = queryOriginal(record.context(), actionNo);
        String reason =
                view.slots().stream()
                        .map(AcceptedTaskSlotVO::currentState)
                        .filter(state -> !"CURRENT_OBSERVATION_REQUIRED".equals(state))
                        .findFirst()
                        .orElse("CURRENT_OBSERVATION_REQUIRED");
        return new OperationVO(
                actionNo,
                null,
                "ACCEPTED",
                "TASK_ACCEPT",
                null,
                record.context().workOrderNo(),
                reason);
    }

    private ExecutionBinding locate(ExecutionScope scope, String task) {
        var row = db.read(() -> fences.findTask(scope.entityNo(), scope.storeNo(), task));
        if (row == null) {
            unavailable("EXECUTION_ORIGINAL_REQUIRED");
        }
        var binding =
                db.json.treeToValue(
                        db.json.readTree(row.getBindingJson()).get("binding"),
                        ExecutionBinding.class);
        require(scope.equals(binding.scope()) && task.equals(binding.taskNo()));
        return binding;
    }

    private void outside() {
        DelegatedIdentityContext.outside();
        schema.requireAvailable();
        stock.requireActionReferenceSchema();
    }

    private TaskAcceptanceRecord replay(
            BtyServiceSubmission row,
            TaskActionContext context,
            Current current,
            String requestHash) {
        var record = read(row);
        authorize(record, current);
        require(record.context().equals(context) && record.requestHash().equals(requestHash));
        return record;
    }

    private static void authorize(TaskAcceptanceRecord record, Current current) {
        if (!record.actorNamespace().equals(current.namespaceHash())
                || !record.actualActorAudit().equals(current.identity())
                || !record.currentPersonnel()
                        .personnelNo()
                        .equals(current.personnel().personnelNo())) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
    }

    private TaskAcceptanceRecord read(BtyServiceSubmission row) {
        return TaskAcceptanceOriginal.read(db, row);
    }

    /**
     * Consumer checks exact frozen policy and concrete whole-bucket provenance, never a success
     * boolean alone.
     */
    public static void validateObservation(
            ResourceQualificationPort.Query query,
            Observation observation,
            OperatorPersonnelFact personnel,
            Instant now) {
        require(
                ResourceQualificationPort.intact(observation)
                        && "RESOURCE_QUALIFICATION_OBSERVATION_V1".equals(observation.schema())
                        && observation.outcome() == ResourceQualificationPort.Outcome.PROVEN
                        && query.equals(observation.query())
                        && ResourceQualificationPort.hash(query).equals(observation.queryHash())
                        && personnel.equals(observation.currentPersonnel())
                        && observation.observedAt().isBefore(observation.validUntil())
                        && !now.isBefore(observation.observedAt())
                        && now.isBefore(observation.validUntil())
                        && observation.groups().size() == query.demands().size());
        var seen = new HashSet<String>();
        for (var demand : query.demands()) {
            var groups =
                    observation.groups().stream()
                            .filter(
                                    g ->
                                            g.resourceNo().equals(demand.resourceNo())
                                                    && g.sourceLineRef()
                                                            .equals(demand.sourceLineRef()))
                            .toList();
            require(groups.size() == 1 && seen.add(demand.sourceLineRef()));
            var group = groups.getFirst();
            require(
                    group.personnelNo().equals(personnel.personnelNo())
                            && group.personnelRevision() == personnel.personnelRevision()
                            && group.resourceRevision() >= 0
                            && group.employment() != null
                            && group.employment().assignmentNo().equals(personnel.assignmentNo())
                            && group.employment().version() == personnel.assignmentRevision()
                            && group.employment().validFrom().equals(personnel.validFrom())
                            && Objects.equals(group.employment().validTo(), personnel.validTo()));
            var buckets = new TreeMap<Instant, CoveredBucket>();
            for (var interval : demand.intervals()) {
                long from = Math.floorDiv(interval.startAt().toEpochMilli(), 300000) * 300000;
                long end = interval.endAt().toEpochMilli();
                long until = end + Math.floorMod(-end, 300000);
                for (long start = from; start < until; start += 300000) {
                    var at = Instant.ofEpochMilli(start);
                    buckets.put(
                            at,
                            new CoveredBucket(
                                    at, Instant.ofEpochMilli(start + 300000), demand.quantity()));
                }
            }
            require(List.copyOf(buckets.values()).equals(group.buckets()));
            var requirements =
                    query.qualifications().stream()
                            .filter(
                                    q ->
                                            q.resourceNo().equals(demand.resourceNo())
                                                    && q.sourceLineRef()
                                                            .equals(demand.sourceLineRef()))
                            .toList();
            require(
                    !requirements.isEmpty()
                            && requirements.stream()
                                    .allMatch(ResourcePlanContract.Qualification::required));
            require(
                    group.skills().stream().distinct().count() == group.skills().size()
                            && group.schedules().stream().distinct().count()
                                    == group.schedules().size()
                            && group.skills().stream()
                                    .allMatch(
                                            w ->
                                                    requirements.stream()
                                                                    .anyMatch(
                                                                            policy ->
                                                                                    Objects.equals(
                                                                                                    w
                                                                                                            .goodsNo(),
                                                                                                    policy
                                                                                                            .goodsNo())
                                                                                            && (w
                                                                                                                    .skuNo()
                                                                                                            == null
                                                                                                    || Objects
                                                                                                            .equals(
                                                                                                                    w
                                                                                                                            .skuNo(),
                                                                                                                    policy
                                                                                                                            .skuNo()))
                                                                                            && (w
                                                                                                                    .storeNo()
                                                                                                            == null
                                                                                                    || w.storeNo()
                                                                                                            .equals(
                                                                                                                    query
                                                                                                                            .storeNo())))
                                                            && group.buckets().stream()
                                                                    .anyMatch(
                                                                            bucket ->
                                                                                    !w.validFrom()
                                                                                                    .isAfter(
                                                                                                            bucket
                                                                                                                    .startAt())
                                                                                            && (w
                                                                                                                    .validTo()
                                                                                                            == null
                                                                                                    || !w.validTo()
                                                                                                            .isBefore(
                                                                                                                    bucket
                                                                                                                            .endAt()))))
                            && group.schedules().stream()
                                    .allMatch(
                                            w ->
                                                    personnel
                                                                    .assignmentNo()
                                                                    .equals(w.assignmentNo())
                                                            && group.buckets().stream()
                                                                    .anyMatch(
                                                                            bucket ->
                                                                                    !w.coveredStart()
                                                                                                    .isAfter(
                                                                                                            bucket
                                                                                                                    .startAt())
                                                                                            && !w.coveredEnd()
                                                                                                    .isBefore(
                                                                                                            bucket
                                                                                                                    .endAt()))));
            for (var bucket : group.buckets()) {
                require(
                        !group.employment().validFrom().isAfter(bucket.startAt())
                                && (group.employment().validTo() == null
                                        || !group.employment().validTo().isBefore(bucket.endAt()))
                                && group.schedules().stream()
                                        .anyMatch(
                                                w ->
                                                        w.ruleNo() != null
                                                                && !w.ruleNo().isBlank()
                                                                && w.version() >= 0
                                                                && personnel
                                                                        .assignmentNo()
                                                                        .equals(w.assignmentNo())
                                                                && !w.coveredStart()
                                                                        .isAfter(bucket.startAt())
                                                                && !w.coveredEnd()
                                                                        .isBefore(bucket.endAt())));
                for (var policy : requirements) {
                    require(
                            group.skills().stream()
                                    .anyMatch(
                                            w ->
                                                    w.skillNo() != null
                                                            && !w.skillNo().isBlank()
                                                            && w.version() >= 0
                                                            && Objects.equals(
                                                                    w.goodsNo(), policy.goodsNo())
                                                            && (w.skuNo() == null
                                                                    || policy.skuNo() != null
                                                                            && w.skuNo()
                                                                                    .equals(
                                                                                            policy
                                                                                                    .skuNo()))
                                                            && (w.storeNo() == null
                                                                    || w.storeNo()
                                                                            .equals(
                                                                                    query
                                                                                            .storeNo()))
                                                            && !w.validFrom()
                                                                    .isAfter(bucket.startAt())
                                                            && (w.validTo() == null
                                                                    || !w.validTo()
                                                                            .isBefore(
                                                                                    bucket
                                                                                            .endAt()))));
                }
            }
            require(now.isBefore(groupValidUntil(group)));
        }
        require(
                observation
                        .validUntil()
                        .equals(
                                observation.groups().stream()
                                        .map(TaskAcceptanceCoordinator::groupValidUntil)
                                        .min(Comparator.naturalOrder())
                                        .orElseThrow()));
    }

    private static QualifiedGroup group(Observation observed, PlannedRoleSlot slot) {
        return observed.groups().stream()
                .filter(
                        g ->
                                g.resourceNo().equals(slot.resourceNo())
                                        && g.sourceLineRef().equals(slot.sourceLineRef()))
                .findFirst()
                .orElseThrow();
    }

    private static boolean sameOwnerGeneration(QualifiedGroup original, QualifiedGroup current) {
        // Expanded window endpoints are clipped to the selected query, so they are not owner
        // revisions.
        return original.resourceNo().equals(current.resourceNo())
                && original.sourceLineRef().equals(current.sourceLineRef())
                && original.personnelNo().equals(current.personnelNo())
                && original.personnelRevision() == current.personnelRevision()
                && original.resourceRevision() == current.resourceRevision()
                && original.employment().equals(current.employment())
                && original.skills().equals(current.skills())
                && original.buckets().equals(current.buckets());
    }

    private static Instant groupValidUntil(QualifiedGroup group) {
        var end =
                group.buckets().stream()
                        .map(CoveredBucket::endAt)
                        .max(Comparator.naturalOrder())
                        .orElseThrow();
        if (group.employment().validTo() != null && group.employment().validTo().isBefore(end)) {
            end = group.employment().validTo();
        }
        return end;
    }

    private static TaskAcceptanceRecord signed(TaskAcceptanceRecord r) {
        return TaskAcceptanceOriginal.signed(r);
    }

    private static TaskActionResultVO result(TaskAcceptanceRecord r, String reason) {
        return new TaskActionResultVO(
                r.actionNo(),
                r.context().taskNo(),
                "ACCEPT_TASK",
                "ACCEPTED",
                r.acceptedAt(),
                r.result(),
                reason == null ? "CURRENT_OBSERVATION_REQUIRED" : reason,
                false);
    }

    private static boolean sameSlot(PlannedRoleSlot first, PlannedRoleSlot second) {
        return first.requirementNo().equals(second.requirementNo())
                && first.ordinal() == second.ordinal();
    }

    private static void requireSource(ExecutionOriginal original) {
        if (original.currentIntent().suspension() != null) {
            unavailable("SOURCE_SUSPENDED");
        }
        if (!"READY_UNCHANGED".equals(original.currentIntent().continuity().state())) {
            unavailable("SOURCE_OBSERVATION_PENDING");
        }
    }

    private static void audit(BtyServiceSubmission row, String actor, Instant at) {
        row.setVersion(0L);
        row.setDeleted(0);
        row.setCreatedAt(local(at));
        row.setUpdatedAt(local(at));
        row.setCreatedBy(actor);
        row.setUpdatedBy(actor);
    }

    private static LocalDateTime local(Instant at) {
        return LocalDateTime.ofInstant(at, ZoneOffset.UTC);
    }

    private static void require(boolean value) {
        if (!value) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }

    private static void unavailable(String reason) {
        throw BizException.of(ErrorCode.BEAUTY_DEPENDENCY_UNAVAILABLE, reason);
    }

    public record SelectedSlot(PlannedRoleSlot slot, long expectedAssignmentRevision) {}

    public record AcceptanceBinding(
            long taskScopeRevision,
            long bindingVersion,
            long sourceFenceRevision,
            InitialPlan plan,
            String requiredRosterHash,
            String referenceHash) {}

    private record Prepared(Capture capture, TaskAcceptanceRecord original) {}

    private record Pointer(
            String assignmentNo, long assignmentRevision, long rowVersion, String actionNo) {}

    private record Capture(
            TaskActionVersions versions,
            AcceptanceBinding binding,
            ExecutionReference reference,
            List<SelectedSlot> selected,
            List<Pointer> pointers,
            List<ResourcePlanContract.Demand> demands,
            List<ResourcePlanContract.Qualification> qualifications) {
        ResourceQualificationPort.Query query(String requestHash) {
            var scope = reference.scope();
            return new ResourceQualificationPort.Query(
                    scope.tenantNo(),
                    scope.entityNo(),
                    scope.storeNo(),
                    requestHash,
                    binding.plan().taskPlanHash(),
                    demands,
                    qualifications);
        }
    }

    public record TaskAcceptanceRecord(
            String schema,
            String purpose,
            String actionNo,
            TaskActionContext context,
            String requestKeyHash,
            String requestHash,
            String actorNamespace,
            DelegatedIdentity actualActorAudit,
            OperatorPersonnelFact currentPersonnel,
            ExecutionBinding originalBinding,
            ExecutionReference reference,
            InitialPlan initialPlan,
            String requiredRosterHash,
            List<SelectedSlot> selected,
            TaskActionVersions requestVersions,
            AcceptanceBinding acceptanceBinding,
            TaskActionObservation initialObservationAudit,
            Observation qualification,
            Instant acceptedAt,
            long publishedTaskRevision,
            List<AcceptedTaskSlotVO> result,
            String recordHash) {
        public TaskAcceptanceRecord {
            selected = List.copyOf(selected);
            result = List.copyOf(result);
        }
    }
}
