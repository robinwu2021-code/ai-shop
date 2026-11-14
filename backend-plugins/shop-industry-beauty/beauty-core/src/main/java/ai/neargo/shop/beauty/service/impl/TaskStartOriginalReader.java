package ai.neargo.shop.beauty.service.impl;

import static ai.neargo.shop.spi.personnel.PersonnelOperatorFactsPort.LinkState.UNLINKED;

import ai.neargo.shop.beauty.dto.AssignServiceTaskReq.Selection;
import ai.neargo.shop.beauty.entity.BtyServiceExecutionFence;
import ai.neargo.shop.beauty.entity.BtyServiceExecutionStockIntent;
import ai.neargo.shop.beauty.entity.BtyServiceSubmission;
import ai.neargo.shop.beauty.entity.BtySourceObservation;
import ai.neargo.shop.beauty.entity.BtyTradeBinding;
import ai.neargo.shop.beauty.mapper.BeautyMappers;
import ai.neargo.shop.beauty.policy.DirectTaskPlanSchemaVerifier;
import ai.neargo.shop.beauty.policy.ServiceExecutionAccess;
import ai.neargo.shop.beauty.policy.ServiceExecutionSchemaVerifier;
import ai.neargo.shop.beauty.policy.ServiceExecutionStockIntentSchemaVerifier;
import ai.neargo.shop.beauty.policy.TaskActionPolicy;
import ai.neargo.shop.beauty.policy.TaskStartSchemaVerifier;
import ai.neargo.shop.beauty.service.ServiceTaskActionService.PlanKind;
import ai.neargo.shop.beauty.service.ServiceTaskActionService.PlannedRoleSlot;
import ai.neargo.shop.beauty.service.ServiceTaskActionService.TaskActionContext;
import ai.neargo.shop.beauty.service.ServiceTaskActionService.TaskActionIntentV2;
import ai.neargo.shop.beauty.service.ServiceTaskActionService.TaskActionVersions;
import ai.neargo.shop.beauty.service.impl.ServiceTaskExecutionServiceImpl.ExecutionOriginal;
import ai.neargo.shop.beauty.service.impl.ServiceTaskExecutionServiceImpl.StartCommand;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.idem.IdempotencyScope;
import ai.neargo.shop.spi.product.ExecutionStockRetentionPort.RetentionLookup;
import ai.neargo.shop.spi.product.ExecutionStockRetentionPreparationObservation;
import ai.neargo.shop.spi.resource.ResourceBookingExecutionEvidencePort.Evidence;
import ai.neargo.shop.spi.resource.ResourcePlanContract;
import ai.neargo.shop.spi.resource.ServiceTaskStartAuthorityPort.AcceptedStaffRef;
import ai.neargo.shop.spi.resource.ServiceTaskStartAuthorityPort.BookingOriginal;
import ai.neargo.shop.spi.resource.ServiceTaskStartAuthorityPort.OriginalState;
import ai.neargo.shop.spi.resource.ServiceTaskStartAuthorityPort.OwnSlot;
import ai.neargo.shop.spi.resource.ServiceTaskStartAuthorityPort.SourceGroup;
import ai.neargo.shop.spi.resource.ServiceTaskStartAuthorityPort.StartAuthority;
import ai.neargo.shop.spi.resource.ServiceTaskStartAuthorityPort.StartAuthorityLookup;
import ai.neargo.shop.spi.resource.ServiceTaskStartAuthorityPort.StartAuthorityOriginal;
import ai.neargo.shop.spi.trade.ExecutionEvidenceContract;
import ai.neargo.shop.spi.trade.ExecutionOriginalEvidence;
import ai.neargo.shop.spi.trade.ExecutionReference;
import ai.neargo.shop.spi.trade.ServiceExecutionContract;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionBinding;
import ai.neargo.shop.spi.trade.ServiceExecutionSourcePort.IntentState;
import ai.neargo.shop.spi.trade.ServiceExecutionSourcePort.SourceContinuity;
import ai.neargo.shop.spi.trade.ServiceExecutionSourcePort.SourceSuspension;
import ai.neargo.shop.spi.trade.ServiceExecutionSourcePort.StartIntent;
import ai.neargo.shop.spi.trade.ServiceExecutionSourcePort.StartIntentLookup;

import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Primary local originals only. No coordinator, action service, owner Port or saved HUMAN
 * dependency.
 */
public final class TaskStartOriginalReader {
    final AppointmentPersistence db;
    final BeautyMappers.ServiceSubmissionMapper submissions;
    final BeautyMappers.ServiceExecutionFenceMapper fences;
    final BeautyMappers.TaskAssignmentSlotMapper slots;
    final BeautyMappers.TradeBindingMapper bindings;
    final BeautyMappers.ServiceExecutionStockIntentMapper stockRows;
    private final BeautyMappers.SourceObservationMapper observations;
    private final BeautyMappers.SourceAllocationGuardMapper guards;
    private final BeautyMappers.WorkOrderMapper orders;
    private final BeautyMappers.ServiceTaskMapper tasks;
    private final ServiceExecutionSchemaVerifier schema;
    private final TaskStartSchemaVerifier startSchema;
    private final DirectTaskPlanSchemaVerifier direct;
    private final ServiceExecutionStockIntentSchemaVerifier stockSchema;

    public TaskStartOriginalReader(
            AppointmentPersistence db,
            ServiceExecutionSchemaVerifier schema,
            DirectTaskPlanSchemaVerifier direct,
            ServiceExecutionStockIntentSchemaVerifier stockSchema,
            BeautyMappers.SourceObservationMapper observations,
            BeautyMappers.SourceAllocationGuardMapper guards,
            BeautyMappers.WorkOrderMapper orders,
            BeautyMappers.ServiceTaskMapper tasks,
            BeautyMappers.ServiceExecutionFenceMapper fences,
            BeautyMappers.ServiceSubmissionMapper submissions,
            BeautyMappers.ServiceExecutionStockIntentMapper stockRows,
            BeautyMappers.TradeBindingMapper bindings,
            BeautyMappers.TaskAssignmentSlotMapper slots,
            TaskStartSchemaVerifier startSchema) {
        this.startSchema = startSchema;
        this.db = db;
        this.schema = schema;
        this.direct = direct;
        this.stockSchema = stockSchema;
        this.observations = observations;
        this.guards = guards;
        this.orders = orders;
        this.tasks = tasks;
        this.fences = fences;
        this.submissions = submissions;
        this.stockRows = stockRows;
        this.bindings = bindings;
        this.slots = slots;
    }

    public <T> T withExecutionOriginal(
            ExecutionBinding binding, Function<ExecutionOriginal, T> action) {
        schema.requireAvailable();
        String expected = requireOriginalSchema(binding);
        return db.tx(() -> action.apply(lock(binding, expected)));
    }

    String requireOriginalSchema(ExecutionBinding binding) {
        var row =
                db.read(
                        () ->
                                submissions.selectSubmission(
                                        binding.scope().entityNo(),
                                        binding.scope().storeNo(),
                                        binding.submissionNo()));
        require(row != null && "START".equals(row.getAction()));
        String family = db.json.readTree(row.getFrozenCommand()).path("schema").asString();
        require(List.of("START_MANUAL_V1", "START_DIRECT_V2").contains(family));
        if ("START_DIRECT_V2".equals(family)) direct.requireAvailable();
        return family;
    }

    void requireStockSchema() {
        stockSchema.requireAvailable();
    }

    RetentionLookup retentionOriginal(ExecutionOriginal original) {
        var b = original.command().intent().binding();
        var row = stockRows.lock(b.scope().entityNo(), b.scope().storeNo(), b.executionNo());
        if (row == null)
            throw BizException.of(
                    ErrorCode.BEAUTY_DEPENDENCY_UNAVAILABLE, "HUMAN_REFERENCE_REQUIRED");
        var lookup = readRetention(db, row);
        require(b.submissionNo().equals(row.getSubmissionNo()));
        requireReference(b, original, lookup.reference(), lookup.reference().grantNo());
        return lookup;
    }

    public ExecutionReference referenceForAction(ExecutionOriginal original) {
        return retentionOriginal(original).reference();
    }

    ExecutionOriginal lock(ExecutionBinding binding, String expectedSchema) {
        var scope = binding.scope();
        var observation =
                observations.selectCanonicalForUpdate(scope.entityNo(), binding.subOrderNo());
        require(
                observation != null
                        && scope.storeNo().equals(observation.getStoreNo())
                        && binding.sourceOperationNo().equals(observation.getCheckoutOperationNo())
                        && binding.orderNo().equals(observation.getOrderNo()));
        require(
                guards.selectSourceForUpdate(
                                scope.entityNo(),
                                "STORE_ORDER",
                                binding.subOrderNo(),
                                binding.sourceLineNo())
                        != null);
        var order =
                orders.selectScopedForUpdate(
                        scope.entityNo(), scope.storeNo(), binding.workOrderNo());
        var task = tasks.selectScopedForUpdate(scope.entityNo(), scope.storeNo(), binding.taskNo());
        require(
                order != null
                        && task != null
                        && order.getWorkOrderNo().equals(task.getWorkOrderNo()));
        var fence = fences.lockOriginal(scope.entityNo(), scope.storeNo(), binding.executionNo());
        if (fence == null) throw BizException.of(ErrorCode.NOT_FOUND);
        require(fence.getBindingJson().equals(ServiceExecutionContract.bindingJson(binding)));
        var start =
                submissions.selectScopedForUpdate(
                        scope.entityNo(), scope.storeNo(), fence.getSubmissionNo());
        var command = command(db, start);
        require(
                expectedSchema.equals(command.schema())
                        && command.intent().binding().equals(binding));
        return new ExecutionOriginal(
                fence,
                start,
                command,
                projection(fence, command, observation),
                order,
                task,
                observation);
    }

    static StartCommand command(AppointmentPersistence db, BtyServiceSubmission row) {
        return commandForRead(db.json, row);
    }

    static StartCommand commandForRead(ObjectMapper json, BtyServiceSubmission row) {
        require(row != null && "START".equals(row.getAction()));
        var command = json.readValue(row.getFrozenCommand(), StartCommand.class);
        require(
                List.of("START_MANUAL_V1", "START_DIRECT_V2").contains(command.schema())
                        && command.intent() != null
                        && command.bindingHash()
                                .equals(
                                        ServiceExecutionContract.bindingHash(
                                                command.intent().binding()))
                        && command.intent().binding().submissionNo().equals(row.getSubmissionNo())
                        && command.requestKeyHash().equals(row.getRequestKeyHash())
                        && row.getPayloadHash().equals(command.intent().binding().payloadHash())
                        && command.actionHash()
                                .equals(
                                        ServiceExecutionContract.actionHash(
                                                command.intent().binding(),
                                                "PREPARE",
                                                command.actionNo(),
                                                0,
                                                command.intent().operatorNamespaceHash(),
                                                new StartIntentLookup(command.intent().binding()),
                                                null)));
        if ("START_DIRECT_V2".equals(command.schema())) {
            DirectTaskPlan.validateStart(row.getFrozenCommand(), command);
        }
        return command;
    }

    static StartIntent projection(
            BtyServiceExecutionFence row, StartCommand command, BtySourceObservation observation) {
        var original = command.intent();
        var binding = original.binding();
        require(
                row.getBindingHash().equals(ServiceExecutionContract.bindingHash(binding))
                        && row.getBindingJson()
                                .equals(ServiceExecutionContract.bindingJson(binding))
                        && row.getSourceIntentHash()
                                .equals(ServiceExecutionContract.intentHash(original))
                        && row.getResourceCommandNo().equals(original.resourceCommandNo())
                        && row.getResourceCommandHash().equals(original.resourceCommandHash())
                        && row.getPlanHash().equals(original.frozenPlanHash())
                        && row.getPayloadHash().equals(binding.payloadHash())
                        && binding.payloadHash()
                                .equals(
                                        ServiceExecutionContract.payloadHash(
                                                binding,
                                                original.personnel(),
                                                original.operatorNamespaceHash(),
                                                original.resourceCommandNo(),
                                                original.resourceCommandHash())));
        SourceSuspension suspension = null;
        if (row.getSuspensionIntentRevision() != null) {
            suspension =
                    new SourceSuspension(
                            row.getFenceNo(),
                            row.getExecutionNo(),
                            row.getBindingHash(),
                            row.getSuspensionIntentRevision(),
                            row.getSuspensionManualEpoch(),
                            row.getSuspensionObservationVersion(),
                            row.getSuspensionObservedRevision(),
                            row.getSuspensionObservedHash(),
                            row.getSuspensionReason(),
                            row.getSuspensionProofHash());
            require(
                    ServiceExecutionContract.suspensionHash(binding, suspension)
                            .equals(suspension.proofHash()));
        }
        return new StartIntent(
                binding,
                IntentState.valueOf(row.getIntentState()),
                row.getSourceFenceRevision(),
                original.admissionKind(),
                original.customerPartyDigest(),
                original.personnel(),
                original.operatorNamespaceHash(),
                original.resourceCommandNo(),
                original.resourceCommandHash(),
                original.frozenPlan(),
                original.frozenPlanHash(),
                continuity(observation, binding, suspension),
                suspension);
    }

    static SourceContinuity continuity(
            BtySourceObservation row, ExecutionBinding binding, SourceSuspension cause) {
        boolean ready =
                cause == null
                        && "MANUAL_OFFLINE".equals(row.getAdmissionKind())
                        && "READY".equals(row.getManualState())
                        && "ADMITTED".equals(row.getManualAdmissionState())
                        && row.getManualObservedAt() != null
                        && row.getManualEpoch() >= binding.sourceEpoch()
                        && Objects.equals(row.getManualRevision(), binding.sourceRevision())
                        && Objects.equals(row.getManualVerifiedRevision(), binding.sourceRevision())
                        && Objects.equals(row.getManualHash(), binding.sourceHash())
                        && Objects.equals(row.getManualVerifiedHash(), binding.sourceHash());
        return new SourceContinuity(
                row.getManualEpoch(),
                row.getVersion(),
                cause != null ? "SUSPENDED" : ready ? "READY_UNCHANGED" : "PENDING",
                row.getManualRevision(),
                row.getManualHash(),
                cause != null ? cause.reasonCode() : ready ? null : "SOURCE_UNKNOWN");
    }

    static RetentionLookup readRetention(
            AppointmentPersistence db, BtyServiceExecutionStockIntent row) {
        var ref = db.json.readValue(row.getReferenceJson(), ExecutionReference.class);
        var lookup = db.json.readValue(row.getLookupJson(), RetentionLookup.class);
        ref.validate();
        require(
                ref.equals(lookup.reference())
                        && row.getReferenceHash().equals(ref.referenceHash())
                        && row.getLookupHash()
                                .equals(
                                        ExecutionEvidenceContract.hash(
                                                "BEAUTY_STOCK_LOOKUP_BYTES_V1", lookup))
                        && row.getTenantNo().equals(ref.scope().tenantNo())
                        && row.getEntityNo().equals(ref.scope().entityNo())
                        && row.getStoreNo().equals(ref.scope().storeNo())
                        && row.getWorkOrderNo().equals(ref.workOrderNo())
                        && row.getTaskNo().equals(ref.taskNo())
                        && row.getFenceNo().equals(ref.sourceFenceNo())
                        && row.getExecutionNo().equals(ref.executionNo())
                        && row.getAttemptNo() == ref.attemptNo()
                        && row.getBindingHash().equals(ref.bindingHash())
                        && row.getSourceIntentHash().equals(ref.sourceIntentHash())
                        && row.getResourceCommandNo().equals(ref.plannedResourceCommandNo())
                        && row.getResourceCommandHash().equals(ref.plannedResourceCommandHash())
                        && row.getPlanHash().equals(ref.planHash())
                        && row.getRetentionNo().equals(lookup.retentionNo())
                        && row.getRequestHash().equals(lookup.requestHash())
                        && row.getVersion() >= 0
                        && row.getObservationEpoch() >= 0
                        && row.getSourceFenceRevision() > 0
                        && row.getDeleted() == 0
                        && row.getReferenceJson().equals(db.json.writeValueAsString(ref))
                        && row.getLookupJson().equals(db.json.writeValueAsString(lookup))
                        && (row.getRouteNo() == null) == (row.getRouteHash() == null)
                        && (row.getEvidenceJson() == null) == (row.getEvidenceHash() == null)
                        && (row.getReceiptJson() == null) == (row.getReceiptHash() == null));
        if (row.getEvidenceJson() != null) {
            var evidence =
                    db.json.readValue(row.getEvidenceJson(), ExecutionOriginalEvidence.class);
            evidence.validate();
            require(
                    ref.equals(evidence.reference())
                            && evidence.proofHash().equals(row.getEvidenceHash()));
        }
        if (row.getReceiptJson() != null) {
            var observation =
                    db.json.readValue(
                            row.getReceiptJson(),
                            ExecutionStockRetentionPreparationObservation.class);
            observation.validate(lookup);
            var receipt = observation.receipt();
            require(
                    receipt.receiptHash().equals(row.getReceiptHash())
                            && (receipt.routeHash() == null
                                    || Objects.equals(row.getRouteNo(), receipt.routeNo())
                                            && Objects.equals(
                                                    row.getRouteHash(), receipt.routeHash())));
        }
        return lookup;
    }

    static void requireReference(
            ExecutionBinding binding,
            ExecutionOriginal original,
            ExecutionReference ref,
            String grantNo) {
        ref.validate();
        var command = original.command();
        var intent = command.intent();
        require(
                binding.scope().equals(ref.scope())
                        && binding.executionNo().equals(ref.executionNo())
                        && binding.taskNo().equals(ref.taskNo())
                        && binding.workOrderNo().equals(ref.workOrderNo())
                        && binding.attemptNo() == ref.attemptNo()
                        && grantNo.equals(ref.grantNo())
                        && ServiceExecutionContract.bindingHash(binding).equals(ref.bindingHash())
                        && binding.payloadHash().equals(ref.payloadHash())
                        && ExecutionEvidenceContract.bindingBytesHash(
                                        ServiceExecutionContract.bindingJson(binding))
                                .equals(ref.originalBindingBytesHash())
                        && ServiceExecutionContract.intentHash(intent)
                                .equals(ref.sourceIntentHash())
                        && binding.sourceFenceNo().equals(ref.sourceFenceNo())
                        && binding.sourceFenceRevision() == ref.sourceFenceRevision()
                        && binding.planHash().equals(ref.planHash())
                        && intent.resourceCommandNo().equals(ref.plannedResourceCommandNo())
                        && intent.resourceCommandHash().equals(ref.plannedResourceCommandHash())
                        && command.actionNo().equals(ref.prepareActionNo())
                        && command.actionHash().equals(ref.prepareActionHash())
                        && command.requestKeyHash().equals(ref.prepareKeyHash()));
    }

    record Facts(TaskActionVersions versions, BtyTradeBinding binding) {}

    Facts facts(ExecutionOriginal original) {
        var b = original.command().intent().binding();
        var current =
                bindings.selectByTaskRevisions(
                        b.scope().entityNo(),
                        b.scope().storeNo(),
                        List.of(b.taskNo()),
                        original.task().getBindingRevision());
        // A revoked binding is a business-generation change, not a parser failure or a replacement
        // original.
        var row = current.size() == 1 ? current.getFirst() : null;
        return new Facts(
                row == null
                        ? null
                        : new TaskActionVersions(
                                original.order().getScopeRevision(),
                                original.task().getVersion(),
                                original.task().getBindingRevision(),
                                row.getVersion(),
                                original.fence().getSourceFenceRevision()),
                row);
    }

    static boolean eligible(ExecutionOriginal o, BtyTradeBinding current) {
        var b = o.command().intent().binding();
        var t = o.task();
        var w = o.order();
        return current != null
                && bindingMatches(current, b)
                && List.of("OPEN", "IN_SERVICE").contains(w.getStatus())
                && !w.getScopeFrozen()
                && t.getScopeRevision() == b.scopeRevision()
                && w.getScopeRevision() >= t.getScopeRevision()
                && t.getBindingRevision() == b.bindingRevision()
                && "PENDING".equals(t.getStatus())
                && t.getStartedAt() == null
                && t.getQty().compareTo(BigDecimal.ONE) == 0
                && "COUNT".equals(t.getUom())
                && t.getDoneQty().signum() == 0
                && t.getVoidedQty().signum() == 0
                && b.definitionVersionNo().equals(t.getDefinitionVersionNo())
                && b.executionSnapshotHash()
                        .equals(IdempotencyScope.requestHash(t.getExecutionSnapshot()));
    }

    private static boolean bindingMatches(BtyTradeBinding row, ExecutionBinding binding) {
        return row.getTaskNo().equals(binding.taskNo())
                && row.getBindingRevision() == binding.bindingRevision()
                && row.getQty().compareTo(BigDecimal.ONE) == 0
                && "COUNT".equals(row.getUom())
                && "STORE_ORDER".equals(row.getSourceKind())
                && row.getBindingNo().equals(binding.bindingNo())
                && row.getSourceNo().equals(binding.subOrderNo())
                && row.getSourceLineRef().equals(binding.sourceLineNo())
                && "MANUAL_OFFLINE".equals(row.getAdmissionKind())
                && Objects.equals(row.getManualSourceRevision(), binding.sourceRevision())
                && Objects.equals(row.getManualSourceHash(), binding.sourceHash());
    }

    /** Every required STAFF pointer is linked to its own genuine immutable SELF acceptance. */
    List<AcceptedStaffRef> acceptedRoster(ExecutionOriginal original, Instant now) {
        var planned = FrozenTaskRolePlan.read(db, original);
        var f = facts(original);
        require(f.versions() != null && eligible(original, f.binding()));
        var reference = referenceForAction(original);
        String required =
                ExecutionEvidenceContract.hash("BEAUTY_REQUIRED_STAFF_V1", planned.slots());
        var b = original.command().intent().binding();
        var scope = b.scope();
        var current = slots.selectForTask(scope.entityNo(), scope.storeNo(), b.taskNo());
        if (current.size() != planned.slots().size()) unavailable("ACCEPTANCE_PENDING");
        var result = new ArrayList<AcceptedStaffRef>();
        for (var slot :
                planned.slots().stream()
                        .sorted(
                                Comparator.comparing(PlannedRoleSlot::requirementNo)
                                        .thenComparingInt(PlannedRoleSlot::ordinal))
                        .toList()) {
            var pointer =
                    slots.lockSlot(
                            scope.entityNo(),
                            scope.storeNo(),
                            b.taskNo(),
                            slot.requirementNo(),
                            slot.ordinal());
            if (pointer == null) unavailable("ACCEPTANCE_PENDING");
            var accept =
                    TaskAcceptanceOriginal.read(
                            db,
                            submissions.selectSubmission(
                                    scope.entityNo(),
                                    scope.storeNo(),
                                    pointer.getAcceptActionNo()));
            var selected =
                    accept.selected().stream()
                            .filter(
                                    s ->
                                            s.slot().requirementNo().equals(slot.requirementNo())
                                                    && s.slot().ordinal() == slot.ordinal())
                            .toList();
            var accepted =
                    accept.result().stream()
                            .filter(
                                    s ->
                                            s.requirementNo().equals(slot.requirementNo())
                                                    && s.ordinal() == slot.ordinal())
                            .toList();
            require(
                    selected.size() == 1
                            && selected.getFirst().slot().equals(slot)
                            && accepted.size() == 1
                            && accept.reference().equals(reference)
                            && accept.originalBinding().equals(b)
                            && accept.initialPlan().equals(planned.initial())
                            && accept.requiredRosterHash().equals(required)
                            && accept.acceptanceBinding().taskScopeRevision()
                                    == original.task().getScopeRevision()
                            && accept.acceptanceBinding().bindingVersion()
                                    == f.versions().bindingVersion()
                            && accept.acceptanceBinding().sourceFenceRevision()
                                    == f.versions().sourceFenceRevision()
                            && accept.acceptanceBinding()
                                    .referenceHash()
                                    .equals(reference.referenceHash())
                            && pointer.getAssignmentNo().equals(accepted.getFirst().assignmentNo())
                            && pointer.getAssignmentRevision()
                                    == accepted.getFirst().assignmentRevision()
                            && pointer.getAcceptRecordHash().equals(accept.recordHash())
                            && pointer.getQualificationHash()
                                    .equals(accept.qualification().observationHash())
                            && pointer.getReferenceHash().equals(reference.referenceHash())
                            && pointer.getPersonnelNo().equals(slot.personnelNo())
                            && pointer.getResourceNo().equals(slot.resourceNo())
                            && pointer.getRoleCode().equals(slot.roleCode())
                            && pointer.getPlanHash().equals(planned.initial().planHash())
                            && pointer.getValidUntil()
                                    .toInstant(ZoneOffset.UTC)
                                    .equals(accepted.getFirst().validUntil())
                            && now.isBefore(accepted.getFirst().validUntil())
                            && accept.currentPersonnel().personnelNo().equals(slot.personnelNo()));
            TaskActionPolicy.resolveInitialSlot(
                    slot, planned.definition(), ResourcePlanContract.decode(planned.taskPlan()));
            result.add(
                    new AcceptedStaffRef(
                            pointer.getAssignmentNo(),
                            pointer.getAssignmentRevision(),
                            slot.requirementNo(),
                            slot.ordinal(),
                            slot.roleCode(),
                            slot.resourceNo(),
                            slot.personnelNo(),
                            accept.actionNo(),
                            accept.recordHash(),
                            accept.qualification().observationHash(),
                            accept.actualActorAudit(),
                            null,
                            accept.currentPersonnel()));
        }
        return List.copyOf(result);
    }

    ExecutionBinding locate(TaskActionContext context) {
        var row =
                db.read(
                        () ->
                                fences.findTask(
                                        context.scope().entityNo(),
                                        context.scope().storeNo(),
                                        context.taskNo()));
        if (row == null) unavailable("EXECUTION_ORIGINAL_REQUIRED");
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

    static void unavailable(String reason) {
        throw BizException.of(ErrorCode.BEAUTY_DEPENDENCY_UNAVAILABLE, reason);
    }

    public record StartOriginal(
            BtyServiceSubmission row,
            TaskStartJournal.Frozen frozen,
            TaskStartJournal.Observation observation,
            ExecutionOriginal execution) {}

    public TaskStartJournal.Frozen frozenOriginal(StartAuthorityLookup lookup) {
        startSchema.requireAvailable();
        var row =
                db.read(
                        () ->
                                submissions.selectSubmission(
                                        lookup.reference().scope().entityNo(),
                                        lookup.reference().scope().storeNo(),
                                        lookup.submissionNo()));
        var frozen = TaskStartJournal.readFrozen(row);
        require(frozen.lookup().equals(lookup));
        return frozen;
    }

    public <T> T withStartOriginal(StartAuthorityLookup lookup, Function<StartOriginal, T> action) {
        var frozen = frozenOriginal(lookup);
        requireStockSchema();
        return withExecutionOriginal(
                frozen.action().originalBinding(),
                execution -> {
                    // B3 root stays in its historical position, before the new action/gate/slot
                    // roots.
                    require(retentionOriginal(execution).equals(frozen.retention()));
                    var scope = lookup.reference().scope();
                    var intent =
                            TaskActionV2Original.read(
                                    submissions.selectScopedForUpdate(
                                            scope.entityNo(),
                                            scope.storeNo(),
                                            frozen.action().actionNo()));
                    require(intent.equals(frozen.action()));
                    var row =
                            submissions.selectScopedForUpdate(
                                    scope.entityNo(), scope.storeNo(), lookup.submissionNo());
                    require(frozen.equals(TaskStartJournal.readFrozen(row)));
                    return action.apply(
                            new StartOriginal(
                                    row,
                                    frozen,
                                    TaskStartJournal.readObservation(row, frozen),
                                    execution));
                });
    }

    TaskStartJournal.Frozen freezeStart(
            String number,
            TaskActionIntentV2 action,
            ExecutionOriginal original,
            String nativeKey,
            String ackActionNo) {
        return freezeStart(number, action, original, nativeKey, ackActionNo, null);
    }

    TaskStartJournal.Frozen freezeStart(
            String number,
            TaskActionIntentV2 action,
            ExecutionOriginal original,
            String nativeKey,
            String ackActionNo,
            Evidence bookingEvidence) {
        var planned = FrozenTaskRolePlan.read(db, original);
        var retention = retentionOriginal(original);
        var stock =
                stockRows.lock(
                        action.context().scope().entityNo(),
                        action.context().scope().storeNo(),
                        action.reference().executionNo());
        if (stock.getReceiptJson() == null) unavailable("FULL_RETENTION_ORIGINAL_REQUIRED");
        var receipt =
                db.json.readValue(
                        stock.getReceiptJson(),
                        ExecutionStockRetentionPreparationObservation.class);
        receipt.validate(retention);
        if (receipt.roster() == null) unavailable("FULL_RETENTION_ORIGINAL_REQUIRED");
        var groups = new ArrayList<SourceGroup>();
        BookingOriginal booking = null;
        if (action.initialPlan().kind() == PlanKind.BOOKING) {
            var allocation = BookingTaskExecutionPlan.freeze(db, action, original, bookingEvidence);
            groups.addAll(allocation.groups());
            booking = allocation.original();
        } else {
            var direct =
                    DirectTaskPlan.decode(
                            DirectTaskPlan.ExecutionPlan.decode(
                                            original.command().intent().frozenPlan())
                                    .directPlan());
            for (var requirement : direct.definition().requirements()) {
                for (var selected :
                        direct.assignments().stream()
                                .filter(a -> a.requirementNo().equals(requirement.requirementNo()))
                                .sorted(Comparator.comparingInt(Selection::ordinal))
                                .toList()) {
                    groups.add(
                            new SourceGroup(
                                    direct.taskNo()
                                            + ":"
                                            + selected.requirementNo()
                                            + ":"
                                            + selected.ordinal(),
                                    selected.requirementNo(),
                                    selected.ordinal(),
                                    requirement.roleCode(),
                                    selected.resourceNo(),
                                    selected.personnelNo()));
                }
            }
        }
        String full = original.command().intent().frozenPlan();
        return new TaskStartJournal.Frozen(
                        "TASK_START_V1",
                        number,
                        action,
                        db.json.readTree(full).path("schema").asString(),
                        full,
                        action.reference().planHash(),
                        planned.taskPlan(),
                        ResourcePlanContract.hash(planned.taskPlan()),
                        groups,
                        booking,
                        retention,
                        receipt.roster(),
                        nativeKey,
                        ackActionNo,
                        null)
                .signed();
    }

    public StartAuthority authorizeStart(
            StartAuthorityLookup lookup,
            ServiceExecutionAccess.Current current,
            Map<String, String> canonical,
            Instant now) {
        return withStartOriginal(
                lookup,
                o -> {
                    var f = o.frozen();
                    var a = f.action();
                    var execution = o.execution();
                    if (o.observation().cancellation() != null
                            || execution.currentIntent().suspension() != null)
                        unavailable("SOURCE_SUSPENDED");
                    if (!"READY_UNCHANGED".equals(execution.currentIntent().continuity().state()))
                        unavailable("SOURCE_OBSERVATION_PENDING");
                    var facts = facts(execution);
                    require(
                            a.expected().equals(facts.versions())
                                    && eligible(execution, facts.binding())
                                    && a.actor().equals(current.identity())
                                    && a.currentPersonnel().equals(current.personnel())
                                    && a.namespaceHash().equals(current.namespaceHash())
                                    && a.canonicalAccountNo()
                                            .equals(
                                                    canonical.get(
                                                            ServiceExecutionContract.canonical(
                                                                    a.actor())))
                                    && f.originalPlanJson()
                                            .equals(execution.command().intent().frozenPlan()));
                    var roster = acceptedRoster(execution, now);
                    require(withoutAccounts(a.acceptedRoster()).equals(roster));
                    for (var member : a.acceptedRoster())
                        require(
                                member.canonicalAccountNo()
                                        .equals(
                                                canonical.get(
                                                        ServiceExecutionContract.canonical(
                                                                member.identity()))));
                    TaskActionPolicy.requireOwnSelections(
                            a.ownSlots(), roster, current.personnel());
                    Instant until = current.personnel().validTo();
                    for (var member : roster) {
                        var pointer =
                                slots.lockSlot(
                                        lookup.reference().scope().entityNo(),
                                        lookup.reference().scope().storeNo(),
                                        lookup.reference().taskNo(),
                                        member.requirementNo(),
                                        member.ordinal());
                        Instant end = pointer.getValidUntil().toInstant(ZoneOffset.UTC);
                        if (until == null || end.isBefore(until)) until = end;
                    }
                    require(until != null && now.isBefore(until));
                    var c = execution.currentIntent().continuity();
                    var continuity =
                            new ExecutionOriginalEvidence.Continuity(
                                    ExecutionOriginalEvidence.ContinuityState.valueOf(c.state()),
                                    c.manualEpoch(),
                                    c.observationVersion(),
                                    c.sourceRevision(),
                                    c.sourceHash(),
                                    c.reasonCode());
                    return new StartAuthority(
                                    "SERVICE_TASK_START_AUTHORITY_V1",
                                    lookup,
                                    o.row().getVersion(),
                                    a.actionNo(),
                                    a.intentHash(),
                                    f.originalPlanSchema(),
                                    f.originalPlanJson(),
                                    f.originalPlanHash(),
                                    f.resourcePlanJson(),
                                    f.resourcePlanHash(),
                                    f.sourceGroups(),
                                    f.booking(),
                                    a.acceptedRoster(),
                                    a.ownSlots().stream()
                                            .map(
                                                    s ->
                                                            new OwnSlot(
                                                                    s.requirementNo(),
                                                                    s.ordinal(),
                                                                    s.assignmentRevision()))
                                            .toList(),
                                    f.retentionRoster(),
                                    continuity,
                                    a.actor(),
                                    a.canonicalAccountNo(),
                                    current.personnel(),
                                    now,
                                    until,
                                    null)
                            .signed();
                });
    }

    static List<AcceptedStaffRef> withoutAccounts(List<AcceptedStaffRef> roster) {
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

    /**
     * Invoked only by the real current-HUMAN provider after an explicit typed UNLINKED owner
     * result.
     */
    public void recordUnlinked(
            StartAuthorityLookup lookup, ServiceExecutionAccess.SelfObservation denied) {
        require(denied.facts() != null && denied.facts().state() == UNLINKED);
        withStartOriginal(
                lookup,
                o -> {
                    require(o.frozen().action().actor().equals(denied.identity()));
                    if (o.observation().cancellation() == null) {
                        var cause =
                                TaskStartJournal.Cancellation.unlinked(
                                        o.frozen(),
                                        new TaskStartJournal.UnlinkedDenial(
                                                denied.identity(), denied.facts()));
                        var now =
                                LocalDateTime.ofInstant(
                                        denied.facts().observedAt(), ZoneOffset.UTC);
                        TaskStartJournal.persist(
                                submissions,
                                o.row(),
                                o.observation()
                                        .next(
                                                TaskStartJournal.Phase.ABORT_REQUESTED,
                                                null,
                                                null,
                                                cause,
                                                cause.cause()),
                                now,
                                now,
                                denied.identity().actorNo());
                    }
                    return null;
                });
    }

    public StartAuthorityOriginal queryStartOriginal(StartAuthorityLookup lookup) {
        return withStartOriginal(
                lookup,
                o -> {
                    var c = o.observation().cancellation();
                    String semantic =
                            ExecutionEvidenceContract.hash(
                                    "BEAUTY_TASK_START_SEMANTIC_V1",
                                    List.of(
                                            o.frozen().action().originalBinding(),
                                            o.frozen().action().initialPlan(),
                                            o.frozen().action().acceptedRoster()));
                    return new StartAuthorityOriginal(
                                    "SERVICE_TASK_START_ORIGINAL_V1",
                                    lookup,
                                    OriginalState.PROVEN,
                                    o.frozen().action().actionNo(),
                                    o.frozen().action().intentHash(),
                                    o.frozen().originalPlanHash(),
                                    semantic,
                                    c != null,
                                    c == null ? null : c.actionNo(),
                                    c == null ? null : c.actionHash(),
                                    c == null ? null : c.cause(),
                                    c == null ? null : c.purpose(),
                                    c == null ? null : c.source(),
                                    c == null ? null : c.core(),
                                    o.row().getVersion(),
                                    null)
                            .signed();
                });
    }

    static void require(boolean value) {
        if (!value) throw BizException.of(ErrorCode.CONFLICT);
    }
}
