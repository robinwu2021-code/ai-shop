package ai.neargo.shop.beauty.service.impl;

import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.beauty.dto.AssignServiceTaskReq;
import ai.neargo.shop.beauty.dto.ServiceTaskAssignmentVO;
import ai.neargo.shop.beauty.dto.WorkOrderTaskSnapshotVO;
import ai.neargo.shop.beauty.entity.BtyServiceSubmission;
import ai.neargo.shop.beauty.entity.BtyServiceTask;
import ai.neargo.shop.beauty.entity.BtySourceObservation;
import ai.neargo.shop.beauty.entity.BtyTradeBinding;
import ai.neargo.shop.beauty.entity.BtyWorkOrder;
import ai.neargo.shop.beauty.mapper.BeautyMappers;
import ai.neargo.shop.beauty.policy.DirectTaskPlanSchemaVerifier;
import ai.neargo.shop.beauty.policy.ServiceExecutionSchemaVerifier;
import ai.neargo.shop.beauty.service.ServiceTaskAssignmentService;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.idem.IdempotencyScope;
import ai.neargo.shop.spi.personnel.PersonnelFactsPort;
import ai.neargo.shop.spi.resource.ResourceBookingPort;
import ai.neargo.shop.spi.resource.ResourceCandidatesPort;
import ai.neargo.shop.spi.resource.ResourceFactsPort;
import ai.neargo.shop.spi.resource.ResourcePlanContract;
import ai.neargo.shop.spi.schedule.ScheduleWindowsPort;
import ai.neargo.shop.spi.security.DelegatedIdentity;
import ai.neargo.shop.spi.trade.ServiceExecutionContract;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionScope;
import ai.neargo.shop.spi.user.MerchantOperatorAccessPort;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Exact coordinator plan publication uses existing source/root locks, never a booking or capacity
 * effect.
 */
public final class ServiceTaskAssignmentServiceImpl implements ServiceTaskAssignmentService {
    private final AppointmentPersistence db;
    private final ServiceSubmissionQuery query;
    private final BeautyMappers.WorkOrderMapper orders;
    private final BeautyMappers.ServiceTaskMapper tasks;
    private final BeautyMappers.TradeBindingMapper bindings;
    private final BeautyMappers.ServiceSubmissionMapper submissions;
    private final BeautyMappers.ServiceExecutionFenceMapper fences;
    private final BeautyMappers.SourceObservationMapper observations;
    private final BeautyMappers.SourceAllocationGuardMapper guards;
    private final ObjectProvider<MerchantOperatorAccessPort> operators;
    private final ObjectProvider<ResourceFactsPort> resources;
    private final ObjectProvider<PersonnelFactsPort> people;
    private final ObjectProvider<ScheduleWindowsPort> schedules;
    private final ObjectProvider<ResourceCandidatesPort> capacities;
    private final Clock clock;
    private final DirectTaskPlanSchemaVerifier schema;
    private final ServiceExecutionSchemaVerifier executionSchema;

    public ServiceTaskAssignmentServiceImpl(
            AppointmentPersistence db,
            ServiceSubmissionQuery query,
            BeautyMappers.WorkOrderMapper orders,
            BeautyMappers.ServiceTaskMapper tasks,
            BeautyMappers.TradeBindingMapper bindings,
            BeautyMappers.ServiceSubmissionMapper submissions,
            BeautyMappers.ServiceExecutionFenceMapper fences,
            BeautyMappers.SourceObservationMapper observations,
            BeautyMappers.SourceAllocationGuardMapper guards,
            ObjectProvider<MerchantOperatorAccessPort> operators,
            ObjectProvider<ResourceFactsPort> resources,
            ObjectProvider<PersonnelFactsPort> people,
            ObjectProvider<ScheduleWindowsPort> schedules,
            ObjectProvider<ResourceCandidatesPort> capacities,
            Clock clock,
            DirectTaskPlanSchemaVerifier schema,
            ServiceExecutionSchemaVerifier executionSchema) {
        this.db = db;
        this.query = query;
        this.orders = orders;
        this.tasks = tasks;
        this.bindings = bindings;
        this.submissions = submissions;
        this.fences = fences;
        this.observations = observations;
        this.guards = guards;
        this.operators = operators;
        this.resources = resources;
        this.people = people;
        this.schedules = schedules;
        this.capacities = capacities;
        this.clock = clock;
        this.schema = schema;
        this.executionSchema = executionSchema;
    }

    @Override
    public ServiceTaskAssignmentVO assignTask(
            String entity,
            String store,
            String actor,
            String workOrder,
            String taskNo,
            AssignServiceTaskReq request,
            String requestKey) {
        DelegatedIdentityContext.outside();
        if (requestKey == null || requestKey.isBlank() || requestKey.length() > 128)
            throw BizException.of(ErrorCode.BAD_REQUEST);
        var identity = authorize(entity, store, actor);
        schema.requireAvailable();
        executionSchema.requireAvailable();
        String namespace = namespace(entity, store, identity);
        String keyHash =
                IdempotencyScope.requestHash("BEAUTY_DIRECT_KEY_V1", namespace, requestKey);
        String requestHash =
                IdempotencyScope.requestHash(
                        "BEAUTY_DIRECT_REQUEST_V1",
                        namespace,
                        workOrder,
                        taskNo,
                        ServiceExecutionContract.canonical(request));
        var prior = db.read(() -> submissions.selectByRequestKey(entity, store, keyHash));
        if (prior != null) return replay(prior, workOrder, taskNo, namespace, requestHash);
        var root = query.root(entity, store, workOrder);
        var task = findTask(root, taskNo);
        requireMutable(root, task, request);
        if (task.getDirectPlanNo() != null) published(db, submissions, root.order(), task);
        else require(task.getDirectPlanHash() == null && task.getDirectPlanRevision() == null);
        var binding = binding(entity, store, taskNo, request.expectedBindingRevision());
        var observation =
                db.read(() -> observations.selectCanonical(entity, store, binding.getSourceNo()));
        requireSource(binding, observation, request.expectedSourceRevision());
        var snapshot =
                db.json.readValue(
                        task.getExecutionSnapshot(),
                        WorkOrderCoordinator.TaskExecutionSnapshot.class);
        require(
                snapshot.schemaVersion() == 1
                        && "UNASSIGNED".equals(snapshot.assignmentState())
                        && snapshot.assignments().isEmpty()
                        && snapshot.plannedStartAt() == null
                        && snapshot.plannedEndAt() == null
                        && snapshot.taskSnapshot()
                                .definitionVersionNo()
                                .equals(task.getDefinitionVersionNo()));
        int originalIndex = -1;
        for (int index = 0; index < root.original().taskRefs().size(); index++) {
            if (taskNo.equals(root.original().taskRefs().get(index).taskNo())) {
                require(originalIndex == -1);
                originalIndex = index;
            }
        }
        require(
                originalIndex >= 0
                        && root.original().frozen() != null
                        && originalIndex < root.original().frozen().definitions().size()
                        && snapshot.taskSnapshot()
                                .equals(root.original().frozen().definitions().get(originalIndex)));
        String plan;
        try {
            plan =
                    DirectTaskPlan.resourcePlan(
                            taskNo,
                            snapshot.taskSnapshot(),
                            request.startAt(),
                            request.assignments());
        } catch (IllegalArgumentException invalidSelection) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        verifyFacts(
                new ExecutionScope("MAIN", entity, store), snapshot.taskSnapshot(), request, plan);
        // The final owner IO is followed by current authority again, before entering any local
        // lock.
        require(identity.equals(authorize(entity, store, actor)));
        try {
            return db.tx(
                    () ->
                            publish(
                                    root,
                                    task,
                                    binding,
                                    observation,
                                    request,
                                    keyHash,
                                    requestHash,
                                    namespace,
                                    actor));
        } catch (DuplicateKeyException collision) {
            require(identity.equals(authorize(entity, store, actor)));
            var original = db.read(() -> submissions.selectByRequestKey(entity, store, keyHash));
            require(original != null);
            return replay(original, workOrder, taskNo, namespace, requestHash);
        }
    }

    private ServiceTaskAssignmentVO publish(
            ServiceSubmissionQuery.Root expectedRoot,
            BtyServiceTask expectedTask,
            BtyTradeBinding expectedBinding,
            BtySourceObservation expectedObservation,
            AssignServiceTaskReq request,
            String keyHash,
            String requestHash,
            String namespace,
            String actor) {
        String entity = expectedRoot.order().getEntityNo(),
                store = expectedRoot.order().getStoreNo();
        var observation =
                observations.selectCanonicalForUpdate(entity, expectedBinding.getSourceNo());
        var guard =
                guards.selectSourceForUpdate(
                        entity,
                        "STORE_ORDER",
                        expectedBinding.getSourceNo(),
                        expectedBinding.getSourceLineRef());
        var root =
                orders.selectScopedForUpdate(entity, store, expectedRoot.order().getWorkOrderNo());
        var lockedBindings =
                bindings.selectDirectPlanBindingForUpdate(
                        entity, store, expectedTask.getTaskNo(), request.expectedBindingRevision());
        var task = tasks.selectScopedForUpdate(entity, store, expectedTask.getTaskNo());
        require(
                root != null
                        && task != null
                        && task.getWorkOrderNo().equals(root.getWorkOrderNo()));
        var prior = submissions.selectByRequestKey(entity, store, keyHash);
        if (prior != null)
            return replay(prior, root.getWorkOrderNo(), task.getTaskNo(), namespace, requestHash);
        requireMutable(query.root(entity, store, root.getWorkOrderNo()), task, request);
        require(submissions.protectedTaskStarts(entity, store, task.getTaskNo()) == 0);
        require(fences.findTask(entity, store, task.getTaskNo()) == null);
        require(lockedBindings.size() == 1);
        var actual = lockedBindings.getFirst();
        require(
                actual.getBindingNo().equals(expectedBinding.getBindingNo())
                        && actual.getVersion().equals(expectedBinding.getVersion())
                        && observation != null
                        && task.getExecutionSnapshot().equals(expectedTask.getExecutionSnapshot())
                        && observation.getVersion().equals(expectedObservation.getVersion())
                        && observation.getManualEpoch().equals(expectedObservation.getManualEpoch())
                        && guard != null
                        && guard.getGuardNo().equals(actual.getGuardNo())
                        && Objects.equals(
                                guard.getManualSourceRevision(), actual.getManualSourceRevision())
                        && Objects.equals(
                                guard.getManualSourceHash(), actual.getManualSourceHash()));
        requireSource(actual, observation, request.expectedSourceRevision());
        var snapshot =
                db.json.readValue(
                        task.getExecutionSnapshot(),
                        WorkOrderCoordinator.TaskExecutionSnapshot.class);
        long planRevision =
                task.getDirectPlanRevision() == null
                        ? 1
                        : Math.addExact(task.getDirectPlanRevision(), 1);
        String number = BizKey.next("BTSU");
        var selections =
                request.assignments().stream()
                        .sorted(
                                Comparator.comparing(AssignServiceTaskReq.Selection::requirementNo)
                                        .thenComparingInt(AssignServiceTaskReq.Selection::ordinal))
                        .toList();
        String resourcePlan =
                DirectTaskPlan.resourcePlan(
                        task.getTaskNo(), snapshot.taskSnapshot(), request.startAt(), selections);
        var plan =
                new DirectTaskPlan(
                        "BEAUTY_DIRECT_PLAN_V1",
                        new ExecutionScope("MAIN", entity, store),
                        root.getWorkOrderNo(),
                        task.getTaskNo(),
                        number,
                        planRevision,
                        Math.addExact(task.getVersion(), 1),
                        root.getScopeRevision(),
                        actual.getBindingNo(),
                        actual.getBindingRevision(),
                        actual.getVersion(),
                        actual.getSourceNo(),
                        actual.getSourceLineRef(),
                        observation.getManualRevision(),
                        observation.getManualHash(),
                        IdempotencyScope.requestHash(task.getExecutionSnapshot()),
                        snapshot.taskSnapshot(),
                        request.startAt(),
                        request.startAt().plusSeconds(snapshot.taskSnapshot().durationMin() * 60L),
                        selections,
                        resourcePlan,
                        ResourcePlanContract.hash(resourcePlan));
        var command =
                new DirectTaskPlan.Command(
                        "DIRECT_PLAN_V1", requestHash, keyHash, namespace, plan, plan.hash());
        var row = new BtyServiceSubmission();
        row.setEntityNo(entity);
        row.setStoreNo(store);
        row.setSubmissionNo(number);
        row.setWorkOrderNo(root.getWorkOrderNo());
        row.setAction("DIRECT_PLAN");
        row.setStatus("BOUND");
        row.setRequestKeyHash(keyHash);
        row.setPayloadHash(plan.hash());
        row.setFrozenCommand(ServiceExecutionContract.canonical(command));
        require(submissions.insert(row) == 1);
        require(
                tasks.publishDirectPlan(
                                entity,
                                store,
                                task.getTaskNo(),
                                task.getVersion(),
                                task.getBindingRevision(),
                                root.getScopeRevision(),
                                task.getDirectPlanNo(),
                                number,
                                planRevision,
                                plan.hash(),
                                actor,
                                LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                        == 1);
        return view(plan);
    }

    @Override
    public ServiceTaskAssignmentVO projectOperation(
            String entity, String store, String actor, String submission) {
        DelegatedIdentityContext.outside();
        authorize(entity, store, actor);
        schema.requireAvailable();
        var row = db.read(() -> submissions.selectSubmission(entity, store, submission));
        var command = read(row);
        var plan = command.plan();
        require(entity.equals(plan.scope().entityNo()) && store.equals(plan.scope().storeNo()));
        findTask(query.root(entity, store, plan.workOrderNo()), plan.taskNo());
        return view(plan);
    }

    private ServiceTaskAssignmentVO replay(
            BtyServiceSubmission row,
            String workOrder,
            String task,
            String namespace,
            String requestHash) {
        var command = read(row);
        require(
                workOrder.equals(command.plan().workOrderNo())
                        && task.equals(command.plan().taskNo())
                        && namespace.equals(command.operatorNamespaceHash())
                        && requestHash.equals(command.requestHash()));
        return view(command.plan());
    }

    static DirectTaskPlan.Command read(BtyServiceSubmission row) {
        require(
                row != null
                        && "DIRECT_PLAN".equals(row.getAction())
                        && "BOUND".equals(row.getStatus())
                        && row.getNextRetryAt() == null
                        && row.getFailureCode() == null);
        try {
            var value = DirectTaskPlan.Command.decode(row.getFrozenCommand());
            require(
                    row.getSubmissionNo().equals(value.plan().planNo())
                            && row.getWorkOrderNo().equals(value.plan().workOrderNo())
                            && row.getEntityNo().equals(value.plan().scope().entityNo())
                            && row.getStoreNo().equals(value.plan().scope().storeNo())
                            && row.getRequestKeyHash().equals(value.requestKeyHash())
                            && row.getPayloadHash().equals(value.planHash()));
            return value;
        } catch (RuntimeException malformed) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }

    static DirectTaskPlan current(
            AppointmentPersistence db,
            BeautyMappers.ServiceSubmissionMapper submissions,
            BeautyMappers.TradeBindingMapper bindings,
            ServiceSubmissionQuery.Root root,
            BtyServiceTask task) {
        if (task.getDirectPlanNo() == null)
            throw BizException.of(
                    ErrorCode.CHECKOUT_UNSUPPORTED_SELECTION, "RESOURCE_PLAN_REQUIRED");
        var plan = published(db, submissions, root.order(), task);
        var selected =
                db.read(
                        () ->
                                bindings.selectByTaskRevisions(
                                        root.order().getEntityNo(),
                                        root.order().getStoreNo(),
                                        List.of(task.getTaskNo()),
                                        task.getBindingRevision()));
        require(selected.size() == 1);
        var binding = selected.getFirst();
        require(
                plan.taskNo().equals(task.getTaskNo())
                        && plan.workOrderNo().equals(task.getWorkOrderNo())
                        && plan.planRevision() == task.getDirectPlanRevision()
                        && plan.hash().equals(task.getDirectPlanHash())
                        && plan.publishedTaskRevision() <= task.getVersion()
                        && plan.scopeRevision() == root.order().getScopeRevision()
                        && plan.bindingNo().equals(binding.getBindingNo())
                        && plan.bindingRevision() == task.getBindingRevision()
                        && plan.bindingVersion() == binding.getVersion()
                        && plan.sourceNo().equals(binding.getSourceNo())
                        && plan.sourceLineNo().equals(binding.getSourceLineRef())
                        && Objects.equals(plan.sourceRevision(), binding.getManualSourceRevision())
                        && plan.sourceHash().equals(binding.getManualSourceHash())
                        && plan.taskSnapshotHash()
                                .equals(IdempotencyScope.requestHash(task.getExecutionSnapshot()))
                        && plan.definition()
                                .definitionVersionNo()
                                .equals(task.getDefinitionVersionNo()));
        return plan;
    }

    /**
     * Display uses immutable local history even if today's funding/source observation has become
     * suspended.
     */
    static DirectTaskPlan published(
            AppointmentPersistence db,
            BeautyMappers.ServiceSubmissionMapper submissions,
            BtyWorkOrder order,
            BtyServiceTask task) {
        require(
                task.getDirectPlanNo() != null
                        && task.getDirectPlanRevision() != null
                        && task.getDirectPlanHash() != null);
        var plan =
                read(db.read(
                                () ->
                                        submissions.selectSubmission(
                                                order.getEntityNo(),
                                                order.getStoreNo(),
                                                task.getDirectPlanNo())))
                        .plan();
        require(
                order.getAppointmentNo() == null
                        && task.getAppointmentItemNo() == null
                        && plan.taskNo().equals(task.getTaskNo())
                        && plan.workOrderNo().equals(order.getWorkOrderNo())
                        && plan.scope().entityNo().equals(order.getEntityNo())
                        && plan.scope().storeNo().equals(order.getStoreNo())
                        && plan.planRevision() == task.getDirectPlanRevision()
                        && plan.hash().equals(task.getDirectPlanHash())
                        && plan.publishedTaskRevision() <= task.getVersion()
                        && plan.taskSnapshotHash()
                                .equals(IdempotencyScope.requestHash(task.getExecutionSnapshot()))
                        && plan.definition()
                                .definitionVersionNo()
                                .equals(task.getDefinitionVersionNo()));
        return plan;
    }

    private DelegatedIdentity authorize(String entity, String store, String actor) {
        var identity = DelegatedIdentityContext.current(actor);
        if (!"MAIN".equals(identity.tenantNo())
                || !"MERCHANT".equals(identity.realm())
                || !("MCH".equals(identity.subjectKind()) || "USR".equals(identity.subjectKind())))
            throw BizException.of(ErrorCode.FORBIDDEN);
        var owner = operators.getIfAvailable();
        if (owner == null) throw BizException.of(ErrorCode.BEAUTY_DEPENDENCY_UNAVAILABLE);
        if (!owner.allowed(
                new MerchantOperatorAccessPort.Query(
                        "MAIN",
                        entity,
                        store,
                        actor,
                        identity.realm(),
                        identity.subjectKind(),
                        "biz:service:task:coordinate"))) throw BizException.of(ErrorCode.FORBIDDEN);
        return identity;
    }

    private String namespace(String entity, String store, DelegatedIdentity identity) {
        return IdempotencyScope.requestHash(
                "BEAUTY_DIRECT_ACTOR_V1",
                identity.tenantNo(),
                entity,
                store,
                identity.realm(),
                identity.subjectKind(),
                identity.actorNo());
    }

    private BtyTradeBinding binding(String entity, String store, String task, long revision) {
        var rows =
                db.read(
                        () ->
                                bindings.selectByTaskRevisions(
                                        entity, store, List.of(task), revision));
        require(rows.size() == 1);
        var row = rows.getFirst();
        require(
                "MANUAL_OFFLINE".equals(row.getAdmissionKind())
                        && "STORE_ORDER".equals(row.getSourceKind())
                        && "COUNT".equals(row.getUom())
                        && BigDecimal.ONE.compareTo(row.getQty()) == 0);
        return row;
    }

    private void requireMutable(
            ServiceSubmissionQuery.Root root, BtyServiceTask task, AssignServiceTaskReq request) {
        require(
                root.order().getAppointmentNo() == null
                        && task.getAppointmentItemNo() == null
                        && !root.order().getScopeFrozen()
                        && List.of("OPEN", "IN_SERVICE").contains(root.order().getStatus())
                        && root.order().getScopeRevision() == request.expectedScopeRevision()
                        && task.getVersion() == request.expectedTaskRevision()
                        && task.getBindingRevision() == request.expectedBindingRevision()
                        && task.getScopeRevision() <= root.order().getScopeRevision()
                        && "PENDING".equals(task.getStatus())
                        && task.getStartedAt() == null
                        && task.getDoneQty().signum() == 0
                        && task.getVoidedQty().signum() == 0
                        && "COUNT".equals(task.getUom())
                        && BigDecimal.ONE.compareTo(task.getQty()) == 0);
    }

    private static void requireSource(
            BtyTradeBinding binding, BtySourceObservation observation, long revision) {
        require(
                observation != null
                        && "MANUAL_OFFLINE".equals(observation.getAdmissionKind())
                        && "READY".equals(observation.getManualState())
                        && "ADMITTED".equals(observation.getManualAdmissionState())
                        && observation.getManualEpoch() > 0
                        && observation.getManualObservedAt() != null
                        && observation.getManualRevision() == revision
                        && Objects.equals(
                                observation.getManualRevision(),
                                observation.getManualVerifiedRevision())
                        && Objects.equals(
                                observation.getManualHash(), observation.getManualVerifiedHash())
                        && Objects.equals(
                                binding.getManualSourceRevision(), observation.getManualRevision())
                        && Objects.equals(
                                binding.getManualSourceHash(), observation.getManualHash()));
    }

    private static BtyServiceTask findTask(ServiceSubmissionQuery.Root root, String task) {
        return root.tasks().stream()
                .filter(t -> task.equals(t.getTaskNo()))
                .findFirst()
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND));
    }

    private static ServiceTaskAssignmentVO view(DirectTaskPlan plan) {
        return new ServiceTaskAssignmentVO(
                plan.planNo(),
                plan.workOrderNo(),
                plan.taskNo(),
                plan.publishedTaskRevision(),
                plan.planRevision(),
                plan.hash(),
                false);
    }

    private void verifyFacts(
            ExecutionScope scope,
            WorkOrderTaskSnapshotVO definition,
            AssignServiceTaskReq request,
            String bytes) {
        if (!request.startAt().isAfter(clock.instant()))
            throw BizException.of(ErrorCode.BAD_REQUEST);
        var plan = ResourcePlanContract.decode(bytes);
        Set<String> resourceNos =
                request.assignments().stream()
                        .map(AssignServiceTaskReq.Selection::resourceNo)
                        .collect(Collectors.toSet());
        Set<String> personnelNos =
                request.assignments().stream()
                        .map(AssignServiceTaskReq.Selection::personnelNo)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet());
        Instant from =
                plan.demands().stream()
                        .flatMap(d -> d.intervals().stream())
                        .map(ResourcePlanContract.Interval::startAt)
                        .min(Instant::compareTo)
                        .orElseThrow();
        Instant to =
                plan.demands().stream()
                        .flatMap(d -> d.intervals().stream())
                        .map(ResourcePlanContract.Interval::endAt)
                        .max(Instant::compareTo)
                        .orElseThrow();
        from = Instant.ofEpochSecond(Math.floorDiv(from.getEpochSecond(), 300) * 300);
        to =
                Instant.ofEpochSecond(
                        Math.floorDiv(to.getEpochSecond(), 300) * 300
                                + (to.getEpochSecond() % 300 == 0 && to.getNano() == 0 ? 0 : 300));
        require(Duration.between(from, to).compareTo(Duration.ofDays(31)) <= 0);
        var resourceOwner = resources.getIfAvailable();
        var staffOwner = people.getIfAvailable();
        var scheduleOwner = schedules.getIfAvailable();
        var capacityOwner = capacities.getIfAvailable();
        if (resourceOwner == null
                || scheduleOwner == null
                || capacityOwner == null
                || !personnelNos.isEmpty() && staffOwner == null)
            throw BizException.of(ErrorCode.BEAUTY_DEPENDENCY_UNAVAILABLE);
        var at = clock.instant();
        var facts =
                resourceOwner.getBatch(
                        new ResourceFactsPort.Query(
                                scope.tenantNo(),
                                scope.entityNo(),
                                scope.storeNo(),
                                resourceNos,
                                at));
        var staff =
                personnelNos.isEmpty()
                        ? List.<PersonnelFactsPort.PersonnelFact>of()
                        : staffOwner.getBatch(
                                new PersonnelFactsPort.Query(
                                        scope.tenantNo(),
                                        scope.entityNo(),
                                        scope.storeNo(),
                                        personnelNos,
                                        at));
        var windows =
                scheduleOwner.getBatch(
                        new ScheduleWindowsPort.Query(
                                scope.tenantNo(),
                                scope.entityNo(),
                                scope.storeNo(),
                                resourceNos,
                                from,
                                to));
        var capacity =
                capacityOwner.capacity(
                        new ResourceCandidatesPort.CapacityQuery(
                                new ResourceBookingPort.Scope(
                                        scope.tenantNo(), scope.entityNo(), scope.storeNo()),
                                resourceNos,
                                from,
                                to,
                                at,
                                null));
        require(
                facts != null
                        && staff != null
                        && windows != null
                        && capacity != null
                        && facts.size() == resourceNos.size()
                        && facts.stream()
                                .map(ResourceFactsPort.ResourceFact::resourceNo)
                                .collect(Collectors.toSet())
                                .equals(resourceNos)
                        && staff.size() == personnelNos.size()
                        && staff.stream()
                                .map(PersonnelFactsPort.PersonnelFact::personnelNo)
                                .collect(Collectors.toSet())
                                .equals(personnelNos));
        Map<String, Integer> planned = new HashMap<>();
        Map<String, Integer> residual = new HashMap<>();
        Map<String, ResourceCandidatesPort.Capacity> slots = new HashMap<>();
        for (var row : capacity) {
            require(
                    resourceNos.contains(row.resourceNo())
                            && "RESOURCE_CAPACITY_V2".equals(row.projectionSchema())
                            && row.capacity() > 0
                            && row.used() >= 0
                            && row.from().getEpochSecond() % 300 == 0
                            && row.from().getNano() == 0
                            && row.to().equals(row.from().plusSeconds(300))
                            && !row.from().isBefore(from)
                            && !row.to().isAfter(to)
                            && slots.put(row.resourceNo() + ":" + row.from().getEpochSecond(), row)
                                    == null);
        }
        for (var choice : request.assignments()) {
            var fact =
                    facts.stream()
                            .filter(f -> choice.resourceNo().equals(f.resourceNo()))
                            .findFirst()
                            .orElseThrow();
            var rule =
                    definition.requirements().stream()
                            .filter(r -> choice.requirementNo().equals(r.requirementNo()))
                            .findFirst()
                            .orElseThrow();
            boolean isStaff = "STAFF".equals(rule.resourceType());
            require(
                    fact.resourceType() != null
                            && rule.resourceType().equals(fact.resourceType().name())
                            && !fact.openOccupied()
                            && fact.capacity() > 0
                            && (isStaff
                                    ? fact.storeNo() == null
                                            && choice.personnelNo().equals(fact.personnelNo())
                                    : scope.storeNo().equals(fact.storeNo())
                                            && fact.personnelNo() == null));
            var demand =
                    plan.demands().stream()
                            .filter(
                                    d ->
                                            d.sourceLineRef()
                                                    .endsWith(
                                                            ":"
                                                                    + choice.requirementNo()
                                                                    + ":"
                                                                    + choice.ordinal()))
                            .findFirst()
                            .orElseThrow();
            var span = demand.intervals().getFirst();
            Instant begin =
                    Instant.ofEpochSecond(
                            Math.floorDiv(span.startAt().getEpochSecond(), 300) * 300);
            Instant end =
                    Instant.ofEpochSecond(
                            Math.floorDiv(span.endAt().getEpochSecond(), 300) * 300
                                    + (span.endAt().getEpochSecond() % 300 == 0
                                                    && span.endAt().getNano() == 0
                                            ? 0
                                            : 300));
            require(
                    windows.stream()
                            .anyMatch(
                                    w ->
                                            choice.resourceNo().equals(w.resourceNo())
                                                    && scope.storeNo().equals(w.storeNo())
                                                    && !w.startAt().isAfter(begin)
                                                    && !w.endAt().isBefore(end)));
            if (isStaff) {
                var person =
                        staff.stream()
                                .filter(p -> choice.personnelNo().equals(p.personnelNo()))
                                .findFirst()
                                .orElseThrow();
                require(
                        scope.storeNo().equals(person.storeNo())
                                && !person.validFrom().isAfter(begin)
                                && (person.validTo() == null || !person.validTo().isBefore(end))
                                && person.qualifications().stream()
                                        .anyMatch(
                                                q ->
                                                        rule.skillGoodsNo().equals(q.goodsNo())
                                                                && (q.skuNo() == null
                                                                        || definition
                                                                                .skuNo()
                                                                                .equals(q.skuNo()))
                                                                && (q.storeNo() == null
                                                                        || scope.storeNo()
                                                                                .equals(
                                                                                        q
                                                                                                .storeNo()))
                                                                && !q.validFrom().isAfter(begin)
                                                                && (q.validTo() == null
                                                                        || !q.validTo()
                                                                                .isBefore(end))));
            }
            for (long second = begin.getEpochSecond();
                    second < end.getEpochSecond();
                    second += 300) {
                var row = slots.get(choice.resourceNo() + ":" + second);
                require(row != null && row.capacity() == fact.capacity());
                String canonical =
                        (isStaff
                                        ? "STAFF:" + choice.personnelNo()
                                        : "RESOURCE:" + choice.resourceNo())
                                + ":"
                                + second;
                planned.merge(canonical, 1, Math::addExact);
                residual.merge(
                        canonical,
                        (isStaff ? Math.min(1, fact.capacity()) : fact.capacity()) - row.used(),
                        Math::min);
            }
        }
        require(
                planned.entrySet().stream()
                        .allMatch(e -> e.getValue() <= residual.getOrDefault(e.getKey(), 0)));
    }

    private static void require(boolean condition) {
        if (!condition) throw BizException.of(ErrorCode.CONFLICT);
    }
}
