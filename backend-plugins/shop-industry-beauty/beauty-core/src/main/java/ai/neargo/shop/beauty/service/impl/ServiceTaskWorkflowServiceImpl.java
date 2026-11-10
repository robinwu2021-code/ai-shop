package ai.neargo.shop.beauty.service.impl;

import static ai.neargo.shop.beauty.service.impl.TaskStartJournal.require;

import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.beauty.dto.AcceptTaskReq;
import ai.neargo.shop.beauty.dto.HandoverTaskReq;
import ai.neargo.shop.beauty.dto.StartServiceTaskReq;
import ai.neargo.shop.beauty.dto.TaskActionResultVO;
import ai.neargo.shop.beauty.dto.TaskWorkflowReq;
import ai.neargo.shop.beauty.dto.TaskWorkflowVO;
import ai.neargo.shop.beauty.entity.BtyServiceParticipation;
import ai.neargo.shop.beauty.entity.BtyServiceStep;
import ai.neargo.shop.beauty.entity.BtyServiceSubmission;
import ai.neargo.shop.beauty.entity.BtyServiceTask;
import ai.neargo.shop.beauty.entity.BtyWorkOrder;
import ai.neargo.shop.beauty.mapper.BeautyMappers;
import ai.neargo.shop.beauty.mapper.TaskWorkflowMapper;
import ai.neargo.shop.beauty.policy.ServiceExecutionAccess;
import ai.neargo.shop.beauty.policy.TaskWorkflowSchemaVerifier;
import ai.neargo.shop.beauty.service.ServiceTaskActionService.AcceptedSlotSelection;
import ai.neargo.shop.beauty.service.ServiceTaskActionService.StartActionCommandV2;
import ai.neargo.shop.beauty.service.ServiceTaskActionService.TaskActionContext;
import ai.neargo.shop.beauty.service.ServiceTaskWorkflowService;
import ai.neargo.shop.beauty.service.impl.TaskWorkflowJournal.Frozen;
import ai.neargo.shop.beauty.service.impl.TaskWorkflowJournal.Observation;
import ai.neargo.shop.beauty.service.impl.TaskWorkflowJournal.Snapshot;
import ai.neargo.shop.common.BaseEntity;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.resource.ResourceFactsPort;
import ai.neargo.shop.spi.resource.ResourceOccupationLifecycleContract.Cancellation;
import ai.neargo.shop.spi.resource.ResourceOccupationLifecycleContract.CancellationOutcome;
import ai.neargo.shop.spi.resource.ResourceOccupationLifecycleContract.CurrentWorkflowProof;
import ai.neargo.shop.spi.resource.ResourceOccupationLifecycleContract.Replacement;
import ai.neargo.shop.spi.resource.ResourceOccupationLifecycleContract.Result;
import ai.neargo.shop.spi.resource.ResourceOccupationLifecycleContract.Segment;
import ai.neargo.shop.spi.resource.ResourceOccupationLifecyclePort;
import ai.neargo.shop.spi.resource.ResourcePlanContract;
import ai.neargo.shop.spi.resource.ServiceTaskStartAuthorityPort.AcceptedStaffRef;
import ai.neargo.shop.spi.resource.ServiceTaskWorkflowAuthorityPort;
import ai.neargo.shop.spi.security.DelegatedIdentity;
import ai.neargo.shop.spi.trade.ExecutionCompletionContract;
import ai.neargo.shop.spi.trade.ExecutionEvidenceContract;
import ai.neargo.shop.spi.trade.ExecutionEvidenceLookup;
import ai.neargo.shop.spi.trade.ExecutionOriginalEvidence;
import ai.neargo.shop.spi.trade.ExecutionReference;
import ai.neargo.shop.spi.trade.ServiceExecutionCompletionPort;
import ai.neargo.shop.spi.trade.ServiceExecutionContract;
import ai.neargo.shop.spi.trade.ServiceExecutionEvidencePort;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionScope;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.GrantState;
import ai.neargo.shop.spi.user.MerchantOperatorAccessPort;

import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import javax.sql.DataSource;

/**
 * Observes real owner effects into actual local participation. Preparation remains a distinct
 * primitive.
 */
public final class ServiceTaskWorkflowServiceImpl
        implements ServiceTaskWorkflowService, ServiceTaskWorkflowAuthorityPort {
    private final AppointmentPersistence db;
    private final BeautyMappers.WorkOrderMapper orders;
    private final BeautyMappers.ServiceTaskMapper tasks;
    private final BeautyMappers.ServiceStepMapper steps;
    private final BeautyMappers.ServiceParticipationMapper people;
    private final BeautyMappers.ServiceSubmissionMapper submissions;
    private final BeautyMappers.ServiceExecutionFenceMapper fences;
    private final TaskWorkflowMapper flows;
    private final ServiceExecutionAccess access;
    private final TaskStartOriginalReader originals;
    private final ObjectProvider<TaskStartCoordinator> starts;
    private final ObjectProvider<TaskAcceptanceCoordinator> acceptance;
    private final ObjectProvider<ResourceOccupationLifecyclePort> resources;
    private final ObjectProvider<ServiceExecutionCompletionPort> completion;
    private final ObjectProvider<ResourceFactsPort> facts;
    private final ObjectProvider<MerchantOperatorAccessPort> operators;
    private final Clock clock;
    private DataSource schemaSource;
    private ObjectProvider<ServiceExecutionEvidencePort> coreEvidence;

    public void setCoreEvidence(ObjectProvider<ServiceExecutionEvidencePort> evidence) {
        this.coreEvidence = evidence;
    }

    public void setWorkflowSchema(DataSource source) {
        this.schemaSource = source;
    }

    public ServiceTaskWorkflowServiceImpl(
            AppointmentPersistence db,
            BeautyMappers.WorkOrderMapper orders,
            BeautyMappers.ServiceTaskMapper tasks,
            BeautyMappers.ServiceStepMapper steps,
            BeautyMappers.ServiceParticipationMapper people,
            BeautyMappers.ServiceSubmissionMapper submissions,
            BeautyMappers.ServiceExecutionFenceMapper fences,
            TaskWorkflowMapper flows,
            ServiceExecutionAccess access,
            TaskStartOriginalReader originals,
            ObjectProvider<TaskStartCoordinator> starts,
            ObjectProvider<TaskAcceptanceCoordinator> acceptance,
            ObjectProvider<ResourceOccupationLifecyclePort> resources,
            ObjectProvider<ServiceExecutionCompletionPort> completion,
            ObjectProvider<ResourceFactsPort> facts,
            ObjectProvider<MerchantOperatorAccessPort> operators,
            Clock clock) {
        this.db = db;
        this.orders = orders;
        this.tasks = tasks;
        this.steps = steps;
        this.people = people;
        this.submissions = submissions;
        this.fences = fences;
        this.flows = flows;
        this.access = access;
        this.originals = originals;
        this.starts = starts;
        this.acceptance = acceptance;
        this.resources = resources;
        this.completion = completion;
        this.facts = facts;
        this.operators = operators;
        this.clock = clock;
    }

    @Override
    public TaskActionResultVO accept(
            String entity,
            String store,
            String actor,
            String workOrder,
            String task,
            AcceptTaskReq request,
            String key) {
        outside(actor);
        requireSchema();
        access.requireSelf(scope(entity, store));
        scopedTask(entity, store, workOrder, task);
        return dependency(acceptance).acceptTask(entity, store, actor, task, request, key);
    }

    @Override
    public TaskWorkflowVO start(
            String entity,
            String store,
            String actor,
            String workOrder,
            String task,
            StartServiceTaskReq request,
            String key) {
        outside(actor);
        requireSchema();
        var scope = scope(entity, store);
        access.requireSelf(scope);
        scopedTask(entity, store, workOrder, task);
        var progress =
                dependency(starts)
                        .start(
                                new TaskActionContext(scope, workOrder, task),
                                new StartActionCommandV2(
                                        request.expectedScopeRevision(),
                                        request.expectedTaskRevision(),
                                        request.expectedBindingRevision(),
                                        request.ownSlots().stream()
                                                .map(
                                                        s ->
                                                                new AcceptedSlotSelection(
                                                                        s.requirementNo(),
                                                                        s.ordinal(),
                                                                        s.assignmentRevision()))
                                                .toList(),
                                        key));
        var frozen = startOriginal(entity, store, workOrder, task);
        if (progress.phase() != TaskStartJournal.Phase.ACKED || progress.stopRequired())
            return view(frozen, progress.submissionNo(), null, false, progress.pendingReason());
        if (!"PENDING".equals(scopedTask(entity, store, workOrder, task).getStatus()))
            return query(entity, store, actor, workOrder, task);
        var current = access.requireSelf(scope);
        require(
                frozen.action().acceptedRoster().stream()
                        .anyMatch(
                                a ->
                                        a.identity().equals(current.identity())
                                                && a.personnel().equals(current.personnel())));
        var applied = progress.resource().applied();
        applied.validate();
        requireCoreActive(frozen.action().reference());
        var compatibility =
                dependency(resources)
                        .validateCurrentStart(frozen.lookup(), frozen.request().lookup());
        compatibility.validate();
        require(
                compatibility.workflow() == null
                        && compatibility.original().equals(frozen.request().lookup())
                        && compatibility.actual().equals(applied.actual())
                        && compatibility.occupationNo().equals(applied.occupationNo()));
        access.requireSame(scope, current);
        originals.withExecutionOriginal(
                frozen.action().originalBinding(),
                o -> {
                    require(
                            o.currentIntent().suspension() == null
                                    && "READY_UNCHANGED"
                                            .equals(o.currentIntent().continuity().state()));
                    var t = o.task();
                    if ("IN_SERVICE".equals(t.getStatus())) return null;
                    require(
                            "PENDING".equals(t.getStatus())
                                    && t.getVersion() == request.expectedTaskRevision()
                                    && t.getBindingRevision() == request.expectedBindingRevision()
                                    && o.order().getScopeRevision()
                                            == request.expectedScopeRevision());
                    require(
                            now().isBefore(compatibility.validUntil())
                                    && !now().isBefore(compatibility.checkedAt()));
                    var at = local(compatibility.checkedAt());
                    var commit =
                            row(
                                    entity,
                                    store,
                                    workOrder,
                                    BizKey.next("BHST"),
                                    "TASK_START_COMMIT",
                                    hash("BEAUTY_TASK_HUMAN_START_KEY_V1", frozen.submissionNo()),
                                    compatibility.proofHash(),
                                    ServiceExecutionContract.canonical(compatibility),
                                    actor);
                    commit.setStatus("APPLIED");
                    require(submissions.insert(commit) == 1);
                    var snapshot =
                            db.json.readValue(
                                    t.getExecutionSnapshot(),
                                    WorkOrderCoordinator.TaskExecutionSnapshot.class);
                    require(
                            snapshot.taskSnapshot().stepsTemplate().size() == 1
                                    && "SERVICE"
                                            .equals(
                                                    snapshot.taskSnapshot()
                                                            .stepsTemplate()
                                                            .getFirst()
                                                            .stepCode()));
                    require(
                            steps.selectForTask(entity, store, workOrder, task).isEmpty()
                                    && people.selectForTask(entity, store, workOrder, task)
                                            .isEmpty());
                    require(
                            tasks.changeStatus(
                                            entity,
                                            store,
                                            task,
                                            t.getVersion(),
                                            t.getBindingRevision(),
                                            "PENDING",
                                            "IN_SERVICE",
                                            at,
                                            actor,
                                            at)
                                    == 1);
                    var step = new BtyServiceStep();
                    stamp(step, entity, actor, at);
                    step.setEntityNo(entity);
                    step.setStepNo(BizKey.next("BTST"));
                    step.setTaskNo(task);
                    step.setStepCode("SERVICE");
                    step.setSequenceNo(0);
                    step.setStatus("IN_PROGRESS");
                    step.setStartedAt(at);
                    require(steps.insert(step) == 1);
                    for (var member : frozen.action().acceptedRoster())
                        append(entity, task, applied.occupationNo(), member, at, null, null, actor);
                    if ("OPEN".equals(o.order().getStatus()))
                        require(
                                flows.startRoot(
                                                entity,
                                                store,
                                                workOrder,
                                                o.order().getVersion(),
                                                o.order().getScopeRevision(),
                                                actor,
                                                at)
                                        == 1);
                    return null;
                });
        return query(entity, store, actor, workOrder, task);
    }

    public record HandoverProposal(
            String operationNo,
            TaskStartJournal.Frozen start,
            HandoverTaskReq request,
            Snapshot before,
            String plan,
            String keyHash,
            String requestHash,
            DelegatedIdentity coordinator,
            String hash) {
        HandoverProposal signed() {
            return new HandoverProposal(
                    operationNo,
                    start,
                    request,
                    before,
                    plan,
                    keyHash,
                    requestHash,
                    coordinator,
                    ExecutionEvidenceContract.digest("BEAUTY_HANDOVER_PROPOSAL_V1", this, "hash"));
        }
    }

    @Override
    public String proposeHandover(
            String entity,
            String store,
            String actor,
            String workOrder,
            String task,
            HandoverTaskReq request,
            String key) {
        outside(actor);
        requireSchema();
        var coordinator = DelegatedIdentityContext.current();
        permission(scope(entity, store), "biz:service:task:coordinate");
        var start = startOriginal(entity, store, workOrder, task);
        var before = currentSnapshot(start);
        require(
                before.revision() == request.expectedOccupationRevision()
                        && "OPEN".equals(before.state()));
        var selected =
                before.segments().stream()
                        .filter(
                                s ->
                                        s.segmentNo().equals(request.segmentNo())
                                                && s.endedAt() == null
                                                && s.personnelNo() != null)
                        .findFirst()
                        .orElseThrow(() -> BizException.of(ErrorCode.CONFLICT));
        var ownerFacts =
                dependency(facts)
                        .getBatch(
                                new ResourceFactsPort.Query(
                                        "MAIN",
                                        entity,
                                        store,
                                        Set.of(request.newResourceNo()),
                                        now()));
        require(
                ownerFacts.size() == 1
                        && ownerFacts.getFirst().resourceType() == ResourceFactsPort.Type.STAFF
                        && request.newPersonnelNo().equals(ownerFacts.getFirst().personnelNo()));
        require(
                before.roster().stream()
                                        .filter(a -> a.personnelNo().equals(selected.personnelNo()))
                                        .count()
                                == 1
                        && before.roster().stream()
                                .noneMatch(a -> a.personnelNo().equals(request.newPersonnelNo())));
        var previous =
                before.roster().stream()
                        .filter(a -> a.personnelNo().equals(selected.personnelNo()))
                        .findFirst()
                        .orElseThrow(() -> BizException.of(ErrorCode.CONFLICT));
        var source =
                start.sourceGroups().stream()
                        .filter(
                                g ->
                                        g.requirementNo().equals(previous.requirementNo())
                                                && g.resourceOrdinal() == previous.ordinal())
                        .findFirst()
                        .orElseThrow(() -> BizException.of(ErrorCode.CONFLICT));
        var oldPlan = ResourcePlanContract.decode(currentPlan(start));
        var proposalAt = now();
        var demands =
                oldPlan.demands().stream()
                        .map(
                                d ->
                                        d.resourceNo().equals(selected.resourceNo())
                                                        && d.sourceLineRef()
                                                                .equals(source.sourceLineRef())
                                                ? new ResourcePlanContract.Demand(
                                                        request.newResourceNo(),
                                                        d.sourceLineRef(),
                                                        d.quantity(),
                                                        d.intervals())
                                                : d)
                        .toList();
        var qualification =
                oldPlan.qualifications().stream()
                        .map(
                                q ->
                                        q.resourceNo().equals(selected.resourceNo())
                                                        && q.sourceLineRef()
                                                                .equals(source.sourceLineRef())
                                                ? new ResourcePlanContract.Qualification(
                                                        request.newResourceNo(),
                                                        q.sourceLineRef(),
                                                        q.goodsNo(),
                                                        q.skuNo(),
                                                        q.required())
                                                : q)
                        .toList();
        demands =
                demands.stream()
                        .map(
                                d ->
                                        new ResourcePlanContract.Demand(
                                                d.resourceNo(),
                                                d.sourceLineRef(),
                                                d.quantity(),
                                                d.intervals().stream()
                                                        .filter(
                                                                span ->
                                                                        span.endAt()
                                                                                .isAfter(
                                                                                        proposalAt))
                                                        .map(
                                                                span ->
                                                                        new ResourcePlanContract
                                                                                .Interval(
                                                                                span.startAt()
                                                                                                .isBefore(
                                                                                                        proposalAt)
                                                                                        ? proposalAt
                                                                                        : span
                                                                                                .startAt(),
                                                                                span.endAt()))
                                                        .toList()))
                        .toList();
        require(demands.stream().allMatch(d -> !d.intervals().isEmpty()));
        String plan = ResourcePlanContract.encode(demands, qualification);
        String keyHash =
                hash(
                        "BEAUTY_HANDOVER_KEY_V1",
                        scope(entity, store),
                        DelegatedIdentityContext.current(),
                        key);
        String requestHash = hash("BEAUTY_HANDOVER_REQUEST_V1", request, workOrder, task);
        permission(scope(entity, store), "biz:service:task:coordinate");
        return originals.withExecutionOriginal(
                start.action().originalBinding(),
                original -> {
                    var order = original.order();
                    var t = original.task();
                    requireVersions(
                            order,
                            t,
                            workOrder,
                            request.expectedScopeRevision(),
                            request.expectedTaskRevision(),
                            request.expectedBindingRevision());
                    var prior =
                            submissions.selectActionKey(
                                    entity, store, "HANDOVER_PROPOSAL", keyHash);
                    if (prior != null) {
                        var p = readProposal(prior);
                        require(p.requestHash().equals(requestHash));
                        return p.operationNo();
                    }
                    requireNoPending(start);
                    require(
                            Objects.equals(
                                    snapshot(start).sourceHistoryHash(),
                                    before.sourceHistoryHash()));
                    var p =
                            new HandoverProposal(
                                            BizKey.next("BHOP"),
                                            start,
                                            request,
                                            before,
                                            plan,
                                            keyHash,
                                            requestHash,
                                            coordinator,
                                            null)
                                    .signed();
                    var row =
                            row(
                                    entity,
                                    store,
                                    workOrder,
                                    p.operationNo(),
                                    "HANDOVER_PROPOSAL",
                                    keyHash,
                                    p.hash(),
                                    ServiceExecutionContract.canonical(p),
                                    actor);
                    row.setStatus("PROPOSED");
                    require(submissions.insert(row) == 1);
                    return p.operationNo();
                });
    }

    @Override
    public TaskWorkflowVO act(
            String entity,
            String store,
            String actor,
            String workOrder,
            String task,
            TaskWorkflowReq request,
            String key) {
        outside(actor);
        requireSchema();
        var scope = scope(entity, store);
        var identity = DelegatedIdentityContext.current();
        var canonical =
                permission(
                        scope,
                        request.action() == TaskWorkflowReq.Action.CLEAN
                                ? "biz:service:task:coordinate"
                                : "biz:service:task:self");
        var current =
                request.action() == TaskWorkflowReq.Action.CLEAN ? null : access.requireSelf(scope);
        var start = startOriginal(entity, store, workOrder, task);
        String keyHash = hash("BEAUTY_TASK_FLOW_KEY_V1", scope, identity, key);
        String requestHash = hash("BEAUTY_TASK_FLOW_REQUEST_V1", workOrder, task, request);
        var prior = db.read(() -> submissions.selectActionKey(entity, store, "TASK_FLOW", keyHash));
        Frozen frozen;
        if (prior != null) {
            frozen = TaskWorkflowJournal.read(db, prior);
            require(
                    frozen.requestHash().equals(requestHash)
                            && frozen.actor().equals(identity)
                            && frozen.start().equals(start));
        } else {
            var before = currentSnapshot(start);
            require(before.revision() == request.expectedOccupationRevision());
            requireSelection(before, request);
            String operationNo = BizKey.next("BTFW");
            var roster = new ArrayList<>(before.roster());
            var replacements = new ArrayList<Replacement>();
            String plan = null;
            if (request.action() == TaskWorkflowReq.Action.HANDOVER) {
                var proposal =
                        readProposal(
                                db.read(
                                        () ->
                                                submissions.selectSubmission(
                                                        entity,
                                                        store,
                                                        request.handoverProposalNo())));
                require(
                        proposal.start().equals(start)
                                && proposal.before().equals(before)
                                && proposal.request()
                                        .newPersonnelNo()
                                        .equals(current.personnel().personnelNo())
                                && proposal.request().expectedTaskRevision()
                                        == request.expectedTaskRevision());
                var segment =
                        before.segments().stream()
                                .filter(
                                        s ->
                                                s.segmentNo().equals(proposal.request().segmentNo())
                                                        && s.endedAt() == null)
                                .findFirst()
                                .orElseThrow(() -> BizException.of(ErrorCode.CONFLICT));
                var old =
                        roster.stream()
                                .filter(m -> m.personnelNo().equals(segment.personnelNo()))
                                .findFirst()
                                .orElseThrow(() -> BizException.of(ErrorCode.CONFLICT));
                roster.remove(old);
                roster.add(
                        new AcceptedStaffRef(
                                old.assignmentNo(),
                                Math.addExact(old.assignmentRevision(), 1),
                                old.requirementNo(),
                                old.ordinal(),
                                old.roleCode(),
                                proposal.request().newResourceNo(),
                                current.personnel().personnelNo(),
                                operationNo,
                                hash("BEAUTY_HANDOVER_ACCEPT_V1", operationNo, proposal.hash()),
                                hash("BEAUTY_HANDOVER_QUALIFICATION_V1", proposal),
                                identity,
                                canonical,
                                current.personnel()));
                replacements.add(
                        new Replacement(segment.segmentNo(), proposal.request().newResourceNo()));
                plan = proposal.plan();
            } else if (request.action() != TaskWorkflowReq.Action.CLEAN) {
                require(
                        current != null
                                && roster.stream()
                                        .anyMatch(
                                                a ->
                                                        a.identity().equals(identity)
                                                                && a.personnel()
                                                                        .equals(
                                                                                current
                                                                                        .personnel())));
            }
            var rules = cleaningRules(scope, before);
            if (current != null) access.requireSame(scope, current);
            final String replacementPlan = plan;
            frozen =
                    originals.withExecutionOriginal(
                            start.action().originalBinding(),
                            original -> {
                                var order = original.order();
                                var t = original.task();
                                requireSourceForNewWork(original, request.action());
                                requireVersions(
                                        order,
                                        t,
                                        workOrder,
                                        request.expectedScopeRevision(),
                                        request.expectedTaskRevision(),
                                        request.expectedBindingRevision());
                                require(
                                        Objects.equals(
                                                snapshot(start).sourceHistoryHash(),
                                                before.sourceHistoryHash()));
                                requireNoPending(start);
                                requireActionState(t, request.action());
                                var f =
                                        new Frozen(
                                                        "BEAUTY_TASK_WORKFLOW_V1",
                                                        operationNo,
                                                        start,
                                                        request,
                                                        keyHash,
                                                        requestHash,
                                                        before,
                                                        replacements,
                                                        roster,
                                                        replacementPlan,
                                                        rules,
                                                        identity,
                                                        current == null
                                                                ? null
                                                                : current.personnel(),
                                                        canonical,
                                                        request.handoverProposalNo(),
                                                        null)
                                                .signed();
                                var row =
                                        row(
                                                entity,
                                                store,
                                                workOrder,
                                                f.operationNo(),
                                                "TASK_FLOW",
                                                keyHash,
                                                f.frozenHash(),
                                                ServiceExecutionContract.canonical(f),
                                                actor);
                                row.setStatus("INTENDED");
                                row.setWorkflowObservationJson(
                                        ServiceExecutionContract.canonical(
                                                new Observation(null, false)));
                                require(submissions.insert(row) == 1);
                                return f;
                            });
        }
        var old = dependency(resources).queryOriginal(frozen.nativeRequest());
        var result = old.orElseGet(() -> dependency(resources).execute(frozen.nativeRequest()));
        if (needsHuman(frozen)) observeAsCurrentHuman(frozen, result, current);
        else observe(frozen, result);
        reconcileCompletion(frozen);
        return query(entity, store, actor, workOrder, task);
    }

    @Override
    public TaskWorkflowVO cancelPending(
            String entity,
            String store,
            String actor,
            String workOrder,
            String task,
            String operationNo) {
        outside(actor);
        requireSchema();
        permission(scope(entity, store), "biz:service:task:coordinate");
        var originalRow = db.read(() -> submissions.selectSubmission(entity, store, operationNo));
        var frozen = TaskWorkflowJournal.read(db, originalRow);
        require(
                frozen.start().action().context().workOrderNo().equals(workOrder)
                        && frozen.start().action().reference().taskNo().equals(task));
        db.tx(
                () -> {
                    orders.selectScopedForUpdate(entity, store, workOrder);
                    tasks.selectScopedForUpdate(entity, store, task);
                    var row = submissions.selectScopedForUpdate(entity, store, operationNo);
                    var o = observation(row);
                    if ("CANCELLED".equals(row.getStatus())) return null;
                    require(
                            List.of("INTENDED", "RESOURCE_APPLIED", "CANCEL_REQUESTED")
                                            .contains(row.getStatus())
                                    || o.cancellationRequested()
                                            && "APPLIED".equals(row.getStatus()));
                    if (!o.cancellationRequested())
                        require(
                                flows.observe(
                                                entity,
                                                store,
                                                operationNo,
                                                row.getVersion(),
                                                ServiceExecutionContract.canonical(
                                                        new Observation(
                                                                o.resource(),
                                                                o.fulfilled(),
                                                                o.humanCommitted(),
                                                                o.currentProof(),
                                                                true,
                                                                o.cancellation())),
                                                "CANCEL_REQUESTED",
                                                "CANCELLATION_PENDING",
                                                actor,
                                                local(now()))
                                        == 1);
                    return null;
                });
        var sealed =
                dependency(resources)
                        .queryCancellationOriginal(frozen.nativeRequest())
                        .orElseGet(
                                () ->
                                        dependency(resources)
                                                .cancelBeforeEffect(frozen.nativeRequest()));
        consumeCancellation(frozen, sealed);
        return query(entity, store, actor, workOrder, task);
    }

    private void consumeCancellation(Frozen f, Cancellation result) {
        result.validate();
        require(result.request().equals(f.nativeRequest()));
        var s = f.start().action().context().scope();
        db.tx(
                () -> {
                    orders.selectScopedForUpdate(
                            s.entityNo(), s.storeNo(), f.start().action().context().workOrderNo());
                    tasks.selectScopedForUpdate(
                            s.entityNo(), s.storeNo(), f.start().action().reference().taskNo());
                    var row =
                            submissions.selectScopedForUpdate(
                                    s.entityNo(), s.storeNo(), f.operationNo());
                    var o = observation(row);
                    if (o.cancellation() != null) {
                        require(o.cancellation().equals(result));
                        return null;
                    }
                    require(o.cancellationRequested());
                    boolean noEffect = result.outcome() == CancellationOutcome.NO_EFFECT_SEALED;
                    require(!noEffect || o.resource() == null);
                    require(
                            flows.observe(
                                            s.entityNo(),
                                            s.storeNo(),
                                            f.operationNo(),
                                            row.getVersion(),
                                            ServiceExecutionContract.canonical(
                                                    new Observation(
                                                            o.resource(),
                                                            o.fulfilled(),
                                                            o.humanCommitted(),
                                                            o.currentProof(),
                                                            false,
                                                            result)),
                                            noEffect
                                                    ? "CANCELLED"
                                                    : o.humanCommitted()
                                                            ? "APPLIED"
                                                            : o.resource() == null
                                                                    ? "INTENDED"
                                                                    : "RESOURCE_APPLIED",
                                            noEffect
                                                    ? "ACTION_CANCELLED_NO_EFFECT"
                                                    : "OWNER_APPLIED",
                                            "WORKFLOW_RECOVERY",
                                            local(now()))
                                    == 1);
                    return null;
                });
        if (result.outcome() == CancellationOutcome.APPLIED) {
            observe(f, result.applied());
            reconcileCompletion(f);
        }
    }

    @Override
    public WorkflowAuthority authorizeCurrent(WorkflowAuthorityLookup lookup) {
        DelegatedIdentityContext.outside();
        var identity = DelegatedIdentityContext.current();
        var s = lookup.reference().scope();
        var row =
                db.read(
                        () ->
                                submissions.selectSubmission(
                                        s.entityNo(), s.storeNo(), lookup.submissionNo()));
        var f = TaskWorkflowJournal.read(db, row);
        require(f.lookup().equals(lookup));
        var observed = observation(row);
        if (observed.cancellationRequested()) {
            var canonical = permission(s, "biz:service:task:coordinate");
            var at = now();
            return new WorkflowAuthority(
                            "SERVICE_TASK_WORKFLOW_AUTHORITY_V1",
                            lookup,
                            Action.CANCEL,
                            f.operationNo(),
                            f.nativeRequest().payloadHash(),
                            f.before().revision(),
                            identity,
                            canonical,
                            null,
                            f.roster(),
                            null,
                            null,
                            f.cleaningRules(),
                            at,
                            at.plusSeconds(15),
                            null)
                    .signed();
        }
        require(!"CANCELLED".equals(row.getStatus()) && f.actor().equals(identity));
        if (needsHuman(f)) requireCoreActive(f.start().action().reference());
        var canonical =
                permission(
                        s,
                        f.request().action() == TaskWorkflowReq.Action.CLEAN
                                ? "biz:service:task:coordinate"
                                : "biz:service:task:self");
        var current =
                f.request().action() == TaskWorkflowReq.Action.CLEAN ? null : access.requireSelf(s);
        require(
                canonical.equals(f.canonicalAccountNo())
                        && (current == null || current.personnel().equals(f.personnel())));
        originals.withExecutionOriginal(
                f.start().action().originalBinding(),
                original -> {
                    var order = original.order();
                    var task = original.task();
                    requireSourceForNewWork(original, f.request().action());
                    requireVersions(
                            order,
                            task,
                            lookup.reference().workOrderNo(),
                            f.request().expectedScopeRevision(),
                            f.request().expectedTaskRevision(),
                            f.request().expectedBindingRevision());
                    requireActionState(task, f.request().action());
                    return null;
                });
        if (current != null) access.requireSame(s, current);
        var at = now();
        var authority =
                new WorkflowAuthority(
                                "SERVICE_TASK_WORKFLOW_AUTHORITY_V1",
                                lookup,
                                Action.valueOf(f.request().action().name()),
                                f.operationNo(),
                                f.nativeRequest().payloadHash(),
                                f.before().revision(),
                                identity,
                                canonical,
                                f.personnel(),
                                f.roster(),
                                f.replacementPlan(),
                                f.replacementPlan() == null
                                        ? null
                                        : ResourcePlanContract.hash(f.replacementPlan()),
                                f.cleaningRules(),
                                at,
                                at.plusSeconds(15),
                                null)
                        .signed();
        authority.validate();
        return authority;
    }

    @Override
    public TaskWorkflowVO query(
            String entity, String store, String actor, String workOrder, String task) {
        outside(actor);
        requireSchema();
        readPermission(scope(entity, store));
        var start = startOriginal(entity, store, workOrder, task);
        requireOwnRead(scope(entity, store), start);
        var history = history(start);
        var last = history.isEmpty() ? null : history.getFirst();
        var observed =
                history.stream()
                        .map(this::observation)
                        .filter(o -> o.resource() != null)
                        .findFirst()
                        .orElse(null);
        boolean fulfilled =
                db.read(
                        () ->
                                history.stream()
                                        .filter(r -> observation(r).fulfilled())
                                        .findAny()
                                        .isPresent());
        return view(
                start,
                last == null ? start.submissionNo() : last.getSubmissionNo(),
                observed == null ? null : observed.resource(),
                fulfilled,
                last != null
                                && ("INTENDED".equals(last.getStatus())
                                        || "CANCEL_REQUESTED".equals(last.getStatus()))
                        ? "OWNER_RESULT_PENDING"
                        : "RESOURCE_APPLIED".equals(last.getStatus())
                                ? "HUMAN_REQUIRED"
                                : "CANCELLED".equals(last.getStatus())
                                        ? "ACTION_CANCELLED_NO_EFFECT"
                                        : null);
    }

    @Override
    public TaskWorkflowVO recover(
            String entity,
            String store,
            String actor,
            String workOrder,
            String task,
            String operationNo) {
        outside(actor);
        requireSchema();
        readPermission(scope(entity, store));
        var row = db.read(() -> submissions.selectSubmission(entity, store, operationNo));
        if (row != null && "TASK_START".equals(row.getAction())) {
            var start = TaskStartJournal.readFrozen(row);
            require(
                    start.action().context().workOrderNo().equals(workOrder)
                            && start.action().reference().taskNo().equals(task));
            requireOwnRead(scope(entity, store), start);
            dependency(starts).recoverOriginal(start.lookup());
            return query(entity, store, actor, workOrder, task);
        }
        var f = TaskWorkflowJournal.read(db, row);
        requireOwnRead(scope(entity, store), f.start());
        require(
                f.start().action().context().workOrderNo().equals(workOrder)
                        && f.start().action().reference().taskNo().equals(task));
        // Recovery only queries the exact original. It never replays a saved HUMAN identity.
        var cancellation =
                observation(row).cancellationRequested()
                        ? dependency(resources).queryCancellationOriginal(f.nativeRequest())
                        : Optional.<Cancellation>empty();
        if (cancellation.isPresent()) consumeCancellation(f, cancellation.get());
        else {
            var actual = dependency(resources).queryOriginal(f.nativeRequest());
            if (actual.isPresent()) {
                observe(f, actual.get());
                reconcileCompletion(f);
            }
        }
        return query(entity, store, actor, workOrder, task);
    }

    /**
     * Machine recovery only reads originals and persists already-applied facts; it never dispatches
     * HUMAN work.
     */
    public int recoverPendingWorkflows(int limit) {
        if (limit < 1 || limit > 100) throw BizException.of(ErrorCode.BAD_REQUEST);
        if (!TaskWorkflowSchemaVerifier.installed(schemaSource)) return 0;
        int count = 0;
        for (var row : db.read(() -> flows.pending(limit))) {
            var f = TaskWorkflowJournal.read(db, row);
            var cancellation =
                    observation(row).cancellationRequested()
                            ? dependency(resources).queryCancellationOriginal(f.nativeRequest())
                            : Optional.<Cancellation>empty();
            if (cancellation.isPresent()) {
                consumeCancellation(f, cancellation.get());
                count++;
            } else {
                var result = dependency(resources).queryOriginal(f.nativeRequest());
                if (result.isPresent()) {
                    observe(f, result.get());
                    reconcileCompletion(f);
                    count++;
                }
            }
        }
        return count;
    }

    private void observeAsCurrentHuman(
            Frozen f, Result result, ServiceExecutionAccess.Current current) {
        var compatible = dependency(resources).validateCurrentWorkflow(f.nativeRequest());
        access.requireSame(f.start().action().context().scope(), current);
        observe(f, result, compatible);
    }

    private static boolean needsHuman(Frozen f) {
        return f.request().action() == TaskWorkflowReq.Action.RESUME
                || f.request().action() == TaskWorkflowReq.Action.HANDOVER;
    }

    private void observe(Frozen f, Result result) {
        observe(f, result, null);
    }

    private void observe(Frozen f, Result result, CurrentWorkflowProof compatible) {
        result.validate();
        require(result.request().equals(f.nativeRequest()));
        var s = f.start().action().context().scope();
        var w = f.start().action().context().workOrderNo();
        var task = f.start().action().reference().taskNo();
        db.tx(
                () -> {
                    var order = orders.selectScopedForUpdate(s.entityNo(), s.storeNo(), w);
                    var t = tasks.selectScopedForUpdate(s.entityNo(), s.storeNo(), task);
                    var row =
                            submissions.selectScopedForUpdate(
                                    s.entityNo(), s.storeNo(), f.operationNo());
                    require(TaskWorkflowJournal.read(db, row).equals(f));
                    var prior = observation(row);
                    if (prior.resource() != null) {
                        require(prior.resource().equals(result));
                        if (prior.humanCommitted() || !needsHuman(f) || compatible == null)
                            return null;
                    }
                    if (needsHuman(f) && compatible == null) {
                        require(
                                flows.observe(
                                                s.entityNo(),
                                                s.storeNo(),
                                                f.operationNo(),
                                                row.getVersion(),
                                                ServiceExecutionContract.canonical(
                                                        new Observation(
                                                                result,
                                                                false,
                                                                false,
                                                                null,
                                                                prior.cancellationRequested(),
                                                                prior.cancellation())),
                                                "RESOURCE_APPLIED",
                                                "HUMAN_REQUIRED",
                                                "WORKFLOW_RECOVERY",
                                                local(now()))
                                        == 1);
                        return null;
                    }
                    if (compatible != null) {
                        compatible.validate();
                        require(
                                compatible.workflow().equals(f.lookup())
                                        && compatible
                                                .original()
                                                .equals(f.nativeRequest().startOriginal())
                                        && compatible
                                                .actual()
                                                .equals(
                                                        storedProgress(f.start())
                                                                .resource()
                                                                .actual())
                                        && compatible.occupationNo().equals(f.occupationNo())
                                        && compatible.occupationRevision() == result.revision()
                                        && compatible.segments().equals(result.segments())
                                        && now().isBefore(compatible.validUntil())
                                        && !now().isBefore(compatible.checkedAt()));
                    }
                    requireVersions(
                            order,
                            t,
                            w,
                            f.request().expectedScopeRevision(),
                            f.request().expectedTaskRevision(),
                            f.request().expectedBindingRevision());
                    var at = local(result.actedAt());
                    var actor = f.actor().actorNo();
                    var action = f.request().action();
                    if (action == TaskWorkflowReq.Action.PAUSE
                            || action == TaskWorkflowReq.Action.RESUME) {
                        String expected =
                                action == TaskWorkflowReq.Action.PAUSE ? "IN_SERVICE" : "PAUSED";
                        String target =
                                action == TaskWorkflowReq.Action.PAUSE ? "PAUSED" : "IN_SERVICE";
                        require(
                                tasks.changeStatus(
                                                s.entityNo(),
                                                s.storeNo(),
                                                task,
                                                t.getVersion(),
                                                t.getBindingRevision(),
                                                expected,
                                                target,
                                                at,
                                                actor,
                                                at)
                                        == 1);
                        if (action == TaskWorkflowReq.Action.PAUSE)
                            endPeople(s, w, task, at, "PAUSE", actor);
                        else
                            for (var member : f.roster())
                                append(
                                        s.entityNo(),
                                        task,
                                        f.occupationNo(),
                                        member,
                                        local(compatible.checkedAt()),
                                        latestParticipation(s, w, task, member.personnelNo()),
                                        "RESUME",
                                        actor);
                    } else if (action == TaskWorkflowReq.Action.HANDOVER) {
                        var active =
                                people.selectForTask(s.entityNo(), s.storeNo(), w, task).stream()
                                        .filter(p -> p.getEndedAt() == null)
                                        .toList();
                        for (var previous : active) {
                            if (f.roster().stream()
                                    .noneMatch(
                                            m ->
                                                    m.personnelNo()
                                                            .equals(previous.getPersonnelNo()))) {
                                require(
                                        people.endParticipation(
                                                        s.entityNo(),
                                                        s.storeNo(),
                                                        previous.getParticipationNo(),
                                                        previous.getVersion(),
                                                        at,
                                                        f.request().reason(),
                                                        actor,
                                                        at)
                                                == 1);
                                var next =
                                        f.roster().stream()
                                                .filter(
                                                        m ->
                                                                f.before().roster().stream()
                                                                        .noneMatch(
                                                                                old ->
                                                                                        old.personnelNo()
                                                                                                .equals(
                                                                                                        m
                                                                                                                .personnelNo())))
                                                .findFirst()
                                                .orElseThrow(
                                                        () -> BizException.of(ErrorCode.CONFLICT));
                                append(
                                        s.entityNo(),
                                        task,
                                        f.occupationNo(),
                                        next,
                                        local(compatible.checkedAt()),
                                        previous.getParticipationNo(),
                                        f.request().reason(),
                                        actor);
                            }
                        }
                        require(
                                flows.advanceTask(
                                                s.entityNo(),
                                                s.storeNo(),
                                                task,
                                                t.getVersion(),
                                                t.getBindingRevision(),
                                                actor,
                                                at)
                                        == 1);
                    } else if (action == TaskWorkflowReq.Action.END) {
                        require(
                                result.endedAt() != null
                                        && result.segments().stream()
                                                .allMatch(seg -> seg.endedAt() != null));
                        var actualSteps = steps.selectForTask(s.entityNo(), s.storeNo(), w, task);
                        require(
                                actualSteps.size() == 1
                                        && "SERVICE".equals(actualSteps.getFirst().getStepCode()));
                        var step = actualSteps.getFirst();
                        require(
                                steps.completeStep(
                                                s.entityNo(),
                                                s.storeNo(),
                                                step.getStepNo(),
                                                task,
                                                step.getVersion(),
                                                at,
                                                ServiceExecutionContract.canonical(result),
                                                actor,
                                                at)
                                        == 1);
                        endPeople(s, w, task, at, "COMPLETED", actor);
                        require(
                                tasks.recordCompletion(
                                                s.entityNo(),
                                                s.storeNo(),
                                                task,
                                                t.getVersion(),
                                                t.getBindingRevision(),
                                                BigDecimal.ONE,
                                                at,
                                                actor,
                                                at)
                                        == 1);
                        flows.finishRoot(
                                s.entityNo(),
                                s.storeNo(),
                                w,
                                order.getVersion(),
                                order.getScopeRevision(),
                                actor,
                                at);
                    }
                    require(
                            flows.observe(
                                            s.entityNo(),
                                            s.storeNo(),
                                            f.operationNo(),
                                            row.getVersion(),
                                            ServiceExecutionContract.canonical(
                                                    new Observation(
                                                            result,
                                                            false,
                                                            true,
                                                            compatible,
                                                            prior.cancellationRequested(),
                                                            prior.cancellation())),
                                            "APPLIED",
                                            null,
                                            actor,
                                            at)
                                    == 1);
                    return null;
                });
    }

    private void reconcileCompletion(Frozen f) {
        if (f.request().action() != TaskWorkflowReq.Action.END) return;
        var request =
                ExecutionCompletionContract.request(
                        f.start().action().reference(), f.nativeRequest(), f.operationNo());
        var receipt = dependency(completion).complete(request);
        receipt.validate();
        if (receipt.outcome() != ExecutionCompletionContract.Outcome.FULFILLED) return;
        require(receipt.proof().request().equals(request));
        var s = f.start().action().context().scope();
        db.tx(
                () -> {
                    var row =
                            submissions.selectScopedForUpdate(
                                    s.entityNo(), s.storeNo(), f.operationNo());
                    var observed = observation(row);
                    if (!observed.fulfilled())
                        require(
                                flows.observe(
                                                s.entityNo(),
                                                s.storeNo(),
                                                f.operationNo(),
                                                row.getVersion(),
                                                ServiceExecutionContract.canonical(
                                                        new Observation(
                                                                observed.resource(),
                                                                true,
                                                                observed.humanCommitted(),
                                                                observed.currentProof(),
                                                                observed.cancellationRequested(),
                                                                observed.cancellation())),
                                                "APPLIED",
                                                null,
                                                "WORKFLOW_RECOVERY",
                                                local(now()))
                                        == 1);
                    return null;
                });
    }

    private List<CleaningRule> cleaningRules(ExecutionScope scope, Snapshot snapshot) {
        var active = snapshot.segments().stream().filter(s -> s.endedAt() == null).toList();
        if (active.isEmpty()) return List.of();
        var rows =
                dependency(facts)
                        .getBatch(
                                new ResourceFactsPort.Query(
                                        "MAIN",
                                        scope.entityNo(),
                                        scope.storeNo(),
                                        active.stream()
                                                .map(Segment::resourceNo)
                                                .collect(Collectors.toSet()),
                                        now()));
        var byId =
                rows.stream()
                        .collect(
                                Collectors.toMap(
                                        ResourceFactsPort.ResourceFact::resourceNo, r -> r));
        return active.stream()
                .map(
                        seg -> {
                            var fact = byId.get(seg.resourceNo());
                            require(fact != null);
                            return new CleaningRule(
                                    seg.logicalDemandNo(),
                                    fact.resourceType() == ResourceFactsPort.Type.STAFF
                                            ? "NONE"
                                            : "MANUAL",
                                    0);
                        })
                .toList();
    }

    private TaskStartCoordinator.StartProgress storedProgress(TaskStartJournal.Frozen start) {
        var c = start.action().context();
        var row =
                db.read(
                        () ->
                                submissions.taskStartForExecution(
                                        c.scope().entityNo(),
                                        c.scope().storeNo(),
                                        start.action().reference().executionNo()));
        require(TaskStartJournal.readFrozen(row).equals(start));
        var o = TaskStartJournal.readObservation(row, start);
        return new TaskStartCoordinator.StartProgress(
                start.submissionNo(),
                o.phase(),
                o.nativeOriginal(),
                o.acknowledgement(),
                o.pendingReason(),
                o.stopRequired());
    }

    private void requireCoreActive(ExecutionReference reference) {
        var evidence =
                dependency(coreEvidence)
                        .queryOriginalEvidence(new ExecutionEvidenceLookup(reference));
        evidence.validate();
        require(
                evidence.reference().equals(reference)
                        && evidence.outcome() == ExecutionOriginalEvidence.EvidenceOutcome.PROVEN
                        && evidence.facts().grantState() == GrantState.STARTED
                        && evidence.facts().unitState()
                                == ExecutionOriginalEvidence.UnitState.RESERVED
                        && reference.executionNo().equals(evidence.facts().currentExecutionNo())
                        && evidence.facts().suspension() == null
                        && evidence.facts().continuity().state()
                                == ExecutionOriginalEvidence.ContinuityState.READY_UNCHANGED);
    }

    private Snapshot currentSnapshot(TaskStartJournal.Frozen start) {
        var local = snapshot(start);
        var current = dependency(resources).queryCurrent(start.request().lookup());
        current.validate();
        require(
                current.original().equals(start.request().lookup())
                        && current.actual().equals(storedProgress(start).resource().actual()));
        return new Snapshot(
                current.occupationNo(),
                current.revision(),
                current.state(),
                current.segments(),
                local.roster(),
                local.sourceHistoryHash());
    }

    private String sourceHistoryHash(TaskStartJournal.Frozen start) {
        return hash(
                "BEAUTY_TASK_FLOW_HISTORY_V1",
                history(start).stream()
                        .map(
                                row ->
                                        List.of(
                                                row.getSubmissionNo(),
                                                row.getVersion(),
                                                row.getStatus(),
                                                row.getWorkflowObservationJson()))
                        .toList());
    }

    private Snapshot snapshot(TaskStartJournal.Frozen start) {
        for (var row : history(start)) {
            var o = observation(row);
            if (o.resource() != null) {
                var f = TaskWorkflowJournal.read(db, row);
                var r = o.resource();
                return new Snapshot(
                        f.occupationNo(),
                        r.revision(),
                        r.state(),
                        r.segments(),
                        f.roster(),
                        sourceHistoryHash(start));
            }
        }
        var progress = storedProgress(start);
        require(progress.phase() == TaskStartJournal.Phase.ACKED);
        var applied = progress.resource().applied();
        return new Snapshot(
                applied.occupationNo(),
                1,
                "OPEN",
                applied.initialEffects().physicalEffects().stream()
                        .map(
                                s ->
                                        new Segment(
                                                s.segmentNo(),
                                                s.logicalDemandNo(),
                                                s.resourceNo(),
                                                s.personnelNo(),
                                                s.quantity(),
                                                s.actualStartAt(),
                                                null,
                                                false,
                                                null))
                        .toList(),
                start.action().acceptedRoster(),
                sourceHistoryHash(start));
    }

    private String currentPlan(TaskStartJournal.Frozen start) {
        for (var row : history(start)) {
            var f = TaskWorkflowJournal.read(db, row);
            if (observation(row).resource() != null && f.replacementPlan() != null)
                return f.replacementPlan();
        }
        return start.resourcePlanJson();
    }

    private List<BtyServiceSubmission> history(TaskStartJournal.Frozen start) {
        var c = start.action().context();
        return db.read(
                () ->
                        flows.history(
                                c.scope().entityNo(),
                                c.scope().storeNo(),
                                c.workOrderNo(),
                                c.taskNo()));
    }

    private void requireNoPending(TaskStartJournal.Frozen start) {
        require(
                history(start).stream()
                        .noneMatch(
                                row ->
                                        List.of("INTENDED", "RESOURCE_APPLIED", "CANCEL_REQUESTED")
                                                .contains(row.getStatus())));
    }

    private Observation observation(BtyServiceSubmission row) {
        return db.json.readValue(row.getWorkflowObservationJson(), Observation.class);
    }

    private TaskStartJournal.Frozen startOriginal(
            String entity, String store, String workOrder, String task) {
        scopedTask(entity, store, workOrder, task);
        var fence = db.read(() -> fences.findTask(entity, store, task));
        if (fence == null) throw BizException.of(ErrorCode.CONFLICT);
        var row =
                db.read(
                        () ->
                                submissions.taskStartForExecution(
                                        entity, store, fence.getExecutionNo()));
        return TaskStartJournal.readFrozen(row);
    }

    private BtyServiceTask scopedTask(String entity, String store, String workOrder, String task) {
        return db.tx(
                () -> {
                    var t = tasks.selectScopedForUpdate(entity, store, task);
                    if (t == null || !workOrder.equals(t.getWorkOrderNo()))
                        throw BizException.of(ErrorCode.NOT_FOUND);
                    return t;
                });
    }

    private HandoverProposal readProposal(BtyServiceSubmission row) {
        require(
                row != null
                        && "HANDOVER_PROPOSAL".equals(row.getAction())
                        && row.getDeleted() == 0);
        var p = db.json.readValue(row.getFrozenCommand(), HandoverProposal.class);
        require(
                p.equals(p.signed())
                        && p.hash().equals(row.getPayloadHash())
                        && p.operationNo().equals(row.getSubmissionNo())
                        && p.start().action().context().scope().entityNo().equals(row.getEntityNo())
                        && p.start().action().context().scope().storeNo().equals(row.getStoreNo())
                        && p.start().action().context().workOrderNo().equals(row.getWorkOrderNo()));
        return p;
    }

    private TaskWorkflowVO view(
            TaskStartJournal.Frozen start,
            String operation,
            Result resource,
            boolean fulfilled,
            String reason) {
        var c = start.action().context();
        var t = scopedTask(c.scope().entityNo(), c.scope().storeNo(), c.workOrderNo(), c.taskNo());
        Snapshot actual = null;
        if (resource != null)
            actual =
                    new Snapshot(
                            resource.request().occupationNo(),
                            resource.revision(),
                            resource.state(),
                            resource.segments(),
                            List.of());
        else {
            var progress = storedProgress(start);
            if (progress.resource() != null && progress.resource().applied() != null) {
                var applied = progress.resource().applied();
                actual =
                        new Snapshot(
                                applied.occupationNo(),
                                1,
                                "OPEN",
                                applied.initialEffects().physicalEffects().stream()
                                        .map(
                                                seg ->
                                                        new Segment(
                                                                seg.segmentNo(),
                                                                seg.logicalDemandNo(),
                                                                seg.resourceNo(),
                                                                seg.personnelNo(),
                                                                seg.quantity(),
                                                                seg.actualStartAt(),
                                                                null,
                                                                false,
                                                                null))
                                        .toList(),
                                List.of());
            }
        }
        var originalProgress = storedProgress(start);
        if (originalProgress.resource() != null && originalProgress.resource().applied() != null) {
            var ownerCurrent = dependency(resources).queryCurrent(start.request().lookup());
            ownerCurrent.validate();
            require(
                    ownerCurrent.original().equals(start.request().lookup())
                            && ownerCurrent.actual().equals(originalProgress.resource().actual()));
            actual =
                    new Snapshot(
                            ownerCurrent.occupationNo(),
                            ownerCurrent.revision(),
                            ownerCurrent.state(),
                            ownerCurrent.segments(),
                            List.of());
            if (ownerCurrent.stopRequired()) reason = "STOP_REQUIRED";
            else if (ownerCurrent.conflictRequired()) reason = "RESOURCE_CONFLICT";
        } else if (reason == null) reason = originalProgress.pendingReason();
        return new TaskWorkflowVO(
                operation,
                c.workOrderNo(),
                c.taskNo(),
                t.getStatus(),
                t.getVersion(),
                actual == null ? null : actual.occupationNo(),
                actual == null ? 0 : actual.revision(),
                actual == null ? null : actual.state(),
                fulfilled,
                stockDisposition(start),
                reason == null && "PENDING".equals(t.getStatus()) ? "HUMAN_REQUIRED" : reason,
                actual == null
                        ? List.of()
                        : actual.segments().stream()
                                .map(
                                        seg ->
                                                new TaskWorkflowVO.Segment(
                                                        seg.segmentNo(),
                                                        seg.resourceNo(),
                                                        seg.personnelNo(),
                                                        seg.startedAt(),
                                                        seg.endedAt(),
                                                        seg.cleaningRequired(),
                                                        seg.cleaningCompletedAt()))
                                .toList());
    }

    private String stockDisposition(TaskStartJournal.Frozen start) {
        // A retention acknowledgement is not an issue command. No provider may fabricate
        // consumption here.
        return start.retention().stockOriginal().needs().isEmpty()
                ? "NOT_REQUIRED"
                : "RETAINED_PENDING_DISPOSITION";
    }

    private void endPeople(
            ExecutionScope s,
            String w,
            String task,
            LocalDateTime at,
            String reason,
            String actor) {
        for (var p : people.selectForTask(s.entityNo(), s.storeNo(), w, task))
            if (p.getEndedAt() == null)
                require(
                        people.endParticipation(
                                        s.entityNo(),
                                        s.storeNo(),
                                        p.getParticipationNo(),
                                        p.getVersion(),
                                        at,
                                        reason,
                                        actor,
                                        at)
                                == 1);
    }

    private String latestParticipation(ExecutionScope s, String w, String task, String personnel) {
        var list =
                people.selectForTask(s.entityNo(), s.storeNo(), w, task).stream()
                        .filter(p -> p.getPersonnelNo().equals(personnel))
                        .toList();
        return list.isEmpty() ? null : list.getLast().getParticipationNo();
    }

    private void append(
            String entity,
            String task,
            String occupation,
            AcceptedStaffRef member,
            LocalDateTime at,
            String from,
            String reason,
            String actor) {
        var p = new BtyServiceParticipation();
        stamp(p, entity, actor, at);
        p.setEntityNo(entity);
        p.setParticipationNo(BizKey.next("BTPA"));
        p.setTaskNo(task);
        p.setPersonnelNo(member.personnelNo());
        p.setAssignmentNo(member.personnel().assignmentNo());
        p.setPlannedAssignmentNo(member.assignmentNo());
        p.setRoleCode(member.roleCode());
        p.setOccupationNo(occupation);
        p.setStartedAt(at);
        p.setHandoverFromNo(from);
        p.setReason(reason);
        p.setQualificationSnapshot(ServiceExecutionContract.canonical(member));
        require(people.insert(p) == 1);
    }

    private BtyServiceSubmission row(
            String entity,
            String store,
            String w,
            String number,
            String action,
            String keyHash,
            String payload,
            String command,
            String actor) {
        var row = new BtyServiceSubmission();
        stamp(row, entity, actor, local(now()));
        row.setEntityNo(entity);
        row.setStoreNo(store);
        row.setWorkOrderNo(w);
        row.setSubmissionNo(number);
        row.setAction(action);
        row.setRequestKeyHash(keyHash);
        row.setPayloadHash(payload);
        row.setFrozenCommand(command);
        return row;
    }

    private static void stamp(BaseEntity row, String entity, String actor, LocalDateTime at) {
        row.setTenantNo("MAIN");
        row.setVersion(0L);
        row.setDeleted(0);
        row.setCreatedBy(actor);
        row.setUpdatedBy(actor);
        row.setCreatedAt(at);
        row.setUpdatedAt(at);
    }

    private static void requireVersions(
            BtyWorkOrder order,
            BtyServiceTask t,
            String w,
            long scope,
            long version,
            long binding) {
        require(
                order != null
                        && t != null
                        && w.equals(t.getWorkOrderNo())
                        && order.getScopeRevision() == scope
                        && !order.getScopeFrozen()
                        && t.getVersion() == version
                        && t.getBindingRevision() == binding
                        && List.of("OPEN", "IN_SERVICE", "SERVICE_FINISHED")
                                .contains(order.getStatus()));
    }

    private static void requireSelection(Snapshot before, TaskWorkflowReq request) {
        if (request.action() == TaskWorkflowReq.Action.CLEAN) {
            require(
                    request.cleaningSegmentNos().stream()
                            .allMatch(
                                    no ->
                                            before.segments().stream()
                                                    .anyMatch(
                                                            seg ->
                                                                    seg.segmentNo().equals(no)
                                                                            && seg.endedAt() != null
                                                                            && seg
                                                                                    .cleaningRequired()
                                                                            && seg
                                                                                            .cleaningCompletedAt()
                                                                                    == null)));
        }
    }

    private static void requireSourceForNewWork(
            ServiceTaskExecutionServiceImpl.ExecutionOriginal original,
            TaskWorkflowReq.Action action) {
        if (action == TaskWorkflowReq.Action.RESUME || action == TaskWorkflowReq.Action.HANDOVER) {
            require(
                    original.currentIntent().suspension() == null
                            && "READY_UNCHANGED"
                                    .equals(original.currentIntent().continuity().state()));
        }
    }

    private static void requireActionState(BtyServiceTask t, TaskWorkflowReq.Action action) {
        require(
                switch (action) {
                    case PAUSE, HANDOVER, END -> "IN_SERVICE".equals(t.getStatus());
                    case RESUME -> "PAUSED".equals(t.getStatus());
                    case CLEAN -> List.of("IN_SERVICE", "PAUSED", "DONE").contains(t.getStatus());
                });
    }

    private String permission(ExecutionScope s, String permission) {
        var identity = DelegatedIdentityContext.current();
        if (!"MAIN".equals(identity.tenantNo()) || !"MERCHANT".equals(identity.realm()))
            throw BizException.of(ErrorCode.FORBIDDEN);
        return dependency(operators)
                .resolveAuthorizedOperator(
                        new MerchantOperatorAccessPort.Query(
                                "MAIN",
                                s.entityNo(),
                                s.storeNo(),
                                identity.actorNo(),
                                identity.realm(),
                                identity.subjectKind(),
                                permission))
                .orElseThrow(() -> BizException.of(ErrorCode.FORBIDDEN))
                .accountNo();
    }

    private void requireOwnRead(ExecutionScope s, TaskStartJournal.Frozen start) {
        var identity = DelegatedIdentityContext.current();
        if (allowed(s, identity, "biz:service:task:view")
                || allowed(s, identity, "biz:service:task:coordinate")) return;
        var current = access.requireSelf(s);
        var personnel = current.personnel().personnelNo();
        boolean own =
                start.action().acceptedRoster().stream()
                                .anyMatch(a -> a.personnelNo().equals(personnel))
                        || history(start).stream()
                                .map(row -> TaskWorkflowJournal.read(db, row))
                                .anyMatch(
                                        f ->
                                                f.roster().stream()
                                                        .anyMatch(
                                                                a ->
                                                                        a.personnelNo()
                                                                                .equals(
                                                                                        personnel)));
        if (!own) throw BizException.of(ErrorCode.FORBIDDEN);
    }

    private boolean allowed(ExecutionScope s, DelegatedIdentity i, String permission) {
        return dependency(operators)
                .allowed(
                        new MerchantOperatorAccessPort.Query(
                                "MAIN",
                                s.entityNo(),
                                s.storeNo(),
                                i.actorNo(),
                                i.realm(),
                                i.subjectKind(),
                                permission));
    }

    private void readPermission(ExecutionScope s) {
        var i = DelegatedIdentityContext.current();
        if (!"MAIN".equals(i.tenantNo()) || !"MERCHANT".equals(i.realm()))
            throw BizException.of(ErrorCode.FORBIDDEN);
        boolean allowed =
                dependency(operators)
                                .allowed(
                                        new MerchantOperatorAccessPort.Query(
                                                "MAIN",
                                                s.entityNo(),
                                                s.storeNo(),
                                                i.actorNo(),
                                                i.realm(),
                                                i.subjectKind(),
                                                "biz:service:task:view"))
                        || dependency(operators)
                                .allowed(
                                        new MerchantOperatorAccessPort.Query(
                                                "MAIN",
                                                s.entityNo(),
                                                s.storeNo(),
                                                i.actorNo(),
                                                i.realm(),
                                                i.subjectKind(),
                                                "biz:service:task:self"))
                        || dependency(operators)
                                .allowed(
                                        new MerchantOperatorAccessPort.Query(
                                                "MAIN",
                                                s.entityNo(),
                                                s.storeNo(),
                                                i.actorNo(),
                                                i.realm(),
                                                i.subjectKind(),
                                                "biz:service:task:coordinate"));
        if (!allowed) throw BizException.of(ErrorCode.FORBIDDEN);
    }

    private void requireSchema() {
        if (!TaskWorkflowSchemaVerifier.installed(schemaSource))
            throw BizException.of(ErrorCode.BEAUTY_DEPENDENCY_UNAVAILABLE);
    }

    private static <T> T dependency(ObjectProvider<T> provider) {
        var value = provider.getIfAvailable();
        if (value == null) throw BizException.of(ErrorCode.BEAUTY_DEPENDENCY_UNAVAILABLE);
        return value;
    }

    private static ExecutionScope scope(String entity, String store) {
        return new ExecutionScope("MAIN", entity, store);
    }

    private static void outside(String actor) {
        DelegatedIdentityContext.outside();
        DelegatedIdentityContext.current(actor);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    private static LocalDateTime local(Instant at) {
        return LocalDateTime.ofInstant(at, ZoneOffset.UTC);
    }

    private static String hash(String domain, Object... values) {
        return ExecutionEvidenceContract.hash(domain, List.of(values));
    }
}
