package ai.neargo.shop.beauty.service.impl;

import ai.neargo.shop.beauty.dto.CustomerIdentityVO;
import ai.neargo.shop.beauty.dto.EntitlementAllocationVO;
import ai.neargo.shop.beauty.entity.BtyServiceSubmission;
import ai.neargo.shop.spi.entitlement.ServiceWorkOrderUsePort.Binding;
import ai.neargo.shop.beauty.dto.WorkOrderDetailVO;
import ai.neargo.shop.beauty.dto.WorkOrderParticipationVO;
import ai.neargo.shop.beauty.dto.WorkOrderStepVO;
import ai.neargo.shop.beauty.dto.WorkOrderTaskVO;
import ai.neargo.shop.beauty.dto.WorkOrderVO;
import ai.neargo.shop.beauty.entity.BtyServiceTask;
import ai.neargo.shop.beauty.entity.BtySourceAllocationGuard;
import ai.neargo.shop.beauty.entity.BtyTradeBinding;
import ai.neargo.shop.beauty.entity.BtyWorkOrder;
import ai.neargo.shop.beauty.entity.BtyWorkOrderContext;
import ai.neargo.shop.beauty.mapper.BeautyMappers;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.Objects;

/** Reads authorized stored facts; templates never become execution facts during projection. */
public final class WorkOrderQuery {
    private final AppointmentPersistence db;
    private final BeautyMappers.WorkOrderContextMapper contexts;
    private final BeautyMappers.ServiceTaskMapper tasks;
    private final BeautyMappers.ServiceStepMapper steps;
    private final BeautyMappers.ServiceParticipationMapper participations;
    private final BeautyMappers.ServiceSubmissionMapper submissions;
    private final BeautyMappers.ReceptionOperationMapper operations;
    private final BeautyMappers.TradeBindingMapper bindings;
    private final BeautyMappers.SourceObservationMapper observations;
    private final BeautyMappers.SourceAllocationGuardMapper guards;

    public WorkOrderQuery(
            AppointmentPersistence db,
            BeautyMappers.WorkOrderContextMapper contexts,
            BeautyMappers.ServiceTaskMapper tasks,
            BeautyMappers.ServiceStepMapper steps,
            BeautyMappers.ServiceParticipationMapper participations,
            BeautyMappers.ServiceSubmissionMapper submissions,
            BeautyMappers.ReceptionOperationMapper operations,
            BeautyMappers.TradeBindingMapper bindings,
            BeautyMappers.SourceObservationMapper observations,
            BeautyMappers.SourceAllocationGuardMapper guards) {
        this.db = db; this.contexts = contexts; this.tasks = tasks;
        this.steps = steps; this.participations = participations; this.submissions = submissions;
        this.operations = operations;
        this.bindings = bindings;
        this.observations = observations;
        this.guards = guards;
    }
    /** The Service has already authorized this persisted root in SQL; callers cannot supply a request number. */
    public WorkOrderDetailVO detail(BtyWorkOrder root) {
        var context = contexts.selectScoped(root.getEntityNo(), root.getStoreNo(), root.getWorkOrderNo());
        boolean legacy = context == null;
        var operation = legacy ? null : operations.selectOpenedForWorkOrder(root.getEntityNo(), root.getStoreNo(),
                root.getWorkOrderNo(), context.getSourceReceptionOperationNo());
        if (!legacy && operation == null) { throw BizException.of(ErrorCode.NOT_FOUND); }
        var rows = tasks.selectForWorkOrder(root.getEntityNo(), root.getStoreNo(), root.getWorkOrderNo());
        var currentBindings =
                bindings.selectCurrentForWorkOrder(
                        root.getEntityNo(), root.getStoreNo(), root.getWorkOrderNo());
        boolean canSubmit = !legacy && !root.getScopeFrozen()
                && List.of("OPEN", "IN_SERVICE").contains(root.getStatus())
                && submissions.selectActiveForWorkOrder(root.getEntityNo(), root.getStoreNo(),
                        root.getWorkOrderNo()).isEmpty()
                && submissions.protectedStartsForWorkOrder(root.getEntityNo(), root.getStoreNo(),
                        root.getWorkOrderNo()) == 0;
        Map<String, String> preparationNumbers = legacy ? Map.of() : submissions
                .selectTaskPreparationRefs(root.getEntityNo(), root.getStoreNo(), root.getWorkOrderNo()).stream()
                .collect(Collectors.toMap(BeautyMappers.TaskPreparationRef::taskNo,
                        BeautyMappers.TaskPreparationRef::operationNo));
        var projected =
                rows.stream()
                        .map(
                                task -> {
                                    WorkOrderCoordinator.TaskExecutionSnapshot execution = null;
                                    if (!legacy) {
                                        execution =
                                                db.json.readValue(
                                                        task.getExecutionSnapshot(),
                                                        WorkOrderCoordinator.TaskExecutionSnapshot
                                                                .class);
                                    }
                                    var taskSteps =
                                            steps
                                                    .selectForTask(
                                                            root.getEntityNo(),
                                                            root.getStoreNo(),
                                                            root.getWorkOrderNo(),
                                                            task.getTaskNo())
                                                    .stream()
                                                    .map(
                                                            s ->
                                                                    new WorkOrderStepVO(
                                                                            s.getStepNo(),
                                                                            s.getStepCode(),
                                                                            s.getSequenceNo(),
                                                                            s.getStatus(),
                                                                            utc(s.getStartedAt()),
                                                                            utc(s.getFinishedAt())))
                                                    .toList();
                                    var people =
                                            participations
                                                    .selectForTask(
                                                            root.getEntityNo(),
                                                            root.getStoreNo(),
                                                            root.getWorkOrderNo(),
                                                            task.getTaskNo())
                                                    .stream()
                                                    .map(
                                                            p ->
                                                                    new WorkOrderParticipationVO(
                                                                            p.getParticipationNo(),
                                                                            p.getPersonnelNo(),
                                                                            p.getRoleCode(),
                                                                            utc(p.getStartedAt()),
                                                                            utc(p.getEndedAt())))
                                                    .toList();
                                    String source = legacy ? "UNKNOWN" : sourceState(root, task, currentBindings);
                                    String prepared = preparationNumbers.get(task.getTaskNo());
                                    boolean preparable = canSubmit && prepared == null
                                            && "BOUND_OBSERVED".equals(source) && "PENDING".equals(task.getStatus())
                                            && "COUNT".equals(task.getUom()) && task.getQty().compareTo(BigDecimal.ONE) == 0
                                            && task.getDoneQty().signum() == 0 && task.getVoidedQty().signum() == 0
                                            && task.getStartedAt() == null && currentBindings.stream().anyMatch(b ->
                                                task.getTaskNo().equals(b.getTaskNo()) && "MANUAL_OFFLINE".equals(b.getAdmissionKind()));
                                    return new WorkOrderTaskVO(
                                            task.getTaskNo(),
                                            task.getAppointmentItemNo(),
                                            task.getScopeRevision(),
                                            task.getSkuNo(),
                                            task.getDefinitionVersionNo(),
                                            task.getQty(),
                                            task.getDoneQty(),
                                            task.getVoidedQty(),
                                            task.getUom(),
                                            task.getStatus(),
                                            task.getBindingRevision(),
                                            execution == null
                                                    ? "UNKNOWN"
                                                    : execution.assignmentState(),
                                            source,
                                            false,
                                            execution == null ? null : execution.plannedStartAt(),
                                            execution == null ? null : execution.plannedEndAt(),
                                            utc(task.getStartedAt()),
                                            execution == null ? List.of() : execution.assignments(),
                                            execution == null ? null : execution.taskSnapshot(),
                                            taskSteps,
                                            people,
                                            legacy ? List.of() : canSubmit && ServiceSubmissionQuery.canFirstBind(task)
                                                    ? List.of("READ", "SUBMIT_SOURCE")
                                                    : preparable ? List.of("READ", "PREPARE_TASK") : List.of("READ"),
                                            task.getVersion(), prepared);
                                })
                        .toList();
        var submission = submissions.selectOpenForWorkOrder(root.getEntityNo(), root.getStoreNo(),
                root.getWorkOrderNo());
        long original = legacy ? 0 : snapshot(context).originalScopeRevision();
        var vo =
                new WorkOrderVO(
                        root.getWorkOrderNo(),
                        root.getAppointmentNo(),
                        root.getScopeRevision(),
                        root.getStatus(),
                        submission == null ? null : submission.getSubmissionNo(),
                        legacy
                                ? "UNKNOWN"
                                : projected.stream()
                                                .anyMatch(
                                                        task ->
                                                                "BLOCKED"
                                                                        .equals(task.sourceState()))
                                        ? "BLOCKED"
                                        : projected.stream()
                                                        .anyMatch(
                                                                task ->
                                                                        "UNKNOWN"
                                                                                .equals(
                                                                                        task
                                                                                                .sourceState()))
                                                ? "UNKNOWN"
                                                : projected.stream()
                                                                .allMatch(
                                                                        task ->
                                                                                "BOUND_OBSERVED"
                                                                                        .equals(
                                                                                                task
                                                                                                        .sourceState()))
                                                        ? "BOUND_OBSERVED"
                                                        : "UNBOUND",
                        legacy ? null : operation.getOperationNo(),
                        legacy ? "UNKNOWN" : operation.getPhase(),
                        projected.stream().map(WorkOrderTaskVO::taskNo).toList(),
                        projected.isEmpty() ? null : projected.getFirst().plannedStartAt(), root.getVersion());
        return new WorkOrderDetailVO(
                vo,
                new CustomerIdentityVO(
                        legacy ? "UNKNOWN" : context.getIdentityKind(),
                        legacy ? null : context.getIdentityRef()),
                original,
                projected,
                legacy ? List.of() : canSubmit ? List.of("READ", "SUBMIT_SERVICES") : List.of("READ"),
                legacy ? List.of() : entitlementAllocations(root));
    }

    private List<EntitlementAllocationVO> entitlementAllocations(BtyWorkOrder root) {
        return submissions.entitlementsForWorkOrder(root.getEntityNo(), root.getStoreNo(),
                root.getWorkOrderNo()).stream().map(row -> {
            var frozen = db.json.readValue(row.getFrozenCommand(), WorkOrderEntitlementService.Frozen.class);
            var receipt = row.getEntitlementReceiptJson() == null ? null : db.json.readValue(
                    row.getEntitlementReceiptJson(), Binding.class);
            return new EntitlementAllocationVO(row.getSubmissionNo(),
                    frozen.origin().taskNo(), frozen.lookup().operationNo(),
                    receipt == null ? null : receipt.bindingNo(), row.getEntitlementUsageNo(),
                    frozen.origin().quantity(), row.getStatus());
        }).toList();
    }

    private String sourceState(
            BtyWorkOrder root, BtyServiceTask task, List<BtyTradeBinding> currentBindings) {
        if (task.getBindingRevision() == 0) {
            return "UNBOUND";
        }
        var rows =
                currentBindings.stream()
                        .filter(row -> row.getTaskNo().equals(task.getTaskNo()))
                        .toList();
        if (rows.isEmpty()) {
            return "UNKNOWN";
        }
        for (var binding : rows) {
            if ("SERVICE_USE".equals(binding.getSourceKind()) && "ENTITLEMENT_USE".equals(binding.getAdmissionKind())) {
                var matches = submissions.entitlementsForWorkOrder(root.getEntityNo(), root.getStoreNo(),
                        root.getWorkOrderNo()).stream()
                        .filter(row -> binding.getSubmissionNo().equals(row.getSubmissionNo())).toList();
                if (matches.size() != 1 || !"BOUND".equals(matches.getFirst().getStatus())) return "UNKNOWN";
                var journal = matches.getFirst();
                var receipt = db.json.readValue(journal.getEntitlementReceiptJson(),
                        Binding.class);
                if (!binding.getBindingNo().equals(receipt.bindingNo())
                        || !binding.getSourceSnapshotHash().equals(receipt.sourceHash())
                        || !task.getTaskNo().equals(receipt.origin().taskNo())
                        || !journal.getEntitlementUsageNo().equals(receipt.usageNo())
                        || binding.getQty().compareTo(BigDecimal.valueOf(receipt.origin().quantity())) != 0) return "BLOCKED";
                var allocation = guards.selectOne(Wrappers.<BtySourceAllocationGuard>lambdaQuery()
                        .eq(BtySourceAllocationGuard::getTenantNo, "MAIN")
                        .eq(BtySourceAllocationGuard::getEntityNo, root.getEntityNo())
                        .eq(BtySourceAllocationGuard::getGuardNo, binding.getGuardNo()));
                if (allocation == null || !"ENTITLEMENT_USE".equals(allocation.getAdmissionKind())
                        || !binding.getSourceSnapshotHash().equals(allocation.getSourceHash())
                        || allocation.getAllocatedQty().compareTo(binding.getQty()) != 0) return "UNKNOWN";
                continue;
            }
            if (!"STORE_ORDER".equals(binding.getSourceKind())) {
                return "UNKNOWN";
            }
            var observation =
                    observations.selectCanonical(
                            root.getEntityNo(), root.getStoreNo(), binding.getSourceNo());
            if ("MANUAL_OFFLINE".equals(binding.getAdmissionKind())) {
                if (observation == null
                        || !"MANUAL_OFFLINE".equals(observation.getAdmissionKind())
                        || !"READY".equals(observation.getManualState())) return "UNKNOWN";
                if (!Objects.equals(binding.getCustomerKind(), observation.getCustomerKind())
                        || !Objects.equals(binding.getBuyerNo(), observation.getBuyerNo())
                        || !Objects.equals(binding.getGuestNo(), observation.getGuestNo())
                        || !Objects.equals(
                                binding.getIdentityRevision(), observation.getIdentityRevision()))
                    return "BLOCKED";
                var guard =
                        guards.selectOne(
                                Wrappers.<BtySourceAllocationGuard>lambdaQuery()
                                        .eq(BtySourceAllocationGuard::getTenantNo, "MAIN")
                                        .eq(
                                                BtySourceAllocationGuard::getEntityNo,
                                                root.getEntityNo())
                                        .eq(
                                                BtySourceAllocationGuard::getGuardNo,
                                                binding.getGuardNo()));
                var line =
                        Arrays.stream(
                                        db.json.readValue(
                                                observation.getManualVerifiedLines(),
                                                ServiceSourceObservation.ManualQuantityLine[]
                                                        .class))
                                .filter(l -> binding.getSourceLineRef().equals(l.sourceLineNo()))
                                .findFirst()
                                .orElse(null);
                if (guard == null
                        || line == null
                        || !"MANUAL_OFFLINE".equals(guard.getAdmissionKind())
                        || !binding.getSourceNo().equals(guard.getSourceNo())
                        || !binding.getSourceLineRef().equals(guard.getSourceLineRef())
                        || guard.getAllocatedQty()
                                        .compareTo(BigDecimal.valueOf(line.availableQuantity()))
                                > 0) return "BLOCKED";
                continue;
            }
            if (observation == null
                    || !"LEGACY_FUNDED".equals(observation.getAdmissionKind())
                    || "UNKNOWN".equals(observation.getState())) {
                return "UNKNOWN";
            }
            if (!"READY".equals(observation.getState())) {
                return "BLOCKED";
            }
            var guard =
                    guards.selectOne(
                            Wrappers.<BtySourceAllocationGuard>lambdaQuery()
                                    .eq(BtySourceAllocationGuard::getTenantNo, "MAIN")
                                    .eq(BtySourceAllocationGuard::getEntityNo, root.getEntityNo())
                                    .eq(
                                            BtySourceAllocationGuard::getGuardNo,
                                            binding.getGuardNo()));
            var line =
                    Arrays.stream(
                                    db.json.readValue(
                                            observation.getVerifiedLines(),
                                            ServiceSourceObservation.QuantityLine[].class))
                            .filter(
                                    candidate ->
                                            binding.getSourceLineRef()
                                                    .equals(candidate.sourceLineNo()))
                            .findFirst()
                            .orElse(null);
            if (guard == null
                    || line == null
                    || !binding.getSourceNo().equals(guard.getSourceNo())
                    || !binding.getSourceLineRef().equals(guard.getSourceLineRef())
                    || guard.getAllocatedQty()
                                    .compareTo(BigDecimal.valueOf(line.refundableQuantity()))
                            > 0) {
                return "BLOCKED";
            }
        }
        return "BOUND_OBSERVED";
    }

    private WorkOrderCoordinator.WorkOrderScopeSnapshot snapshot(BtyWorkOrderContext context) {
        return db.json.readValue(context.getFrozenScope(), WorkOrderCoordinator.WorkOrderScopeSnapshot.class);
    }
    private static OffsetDateTime utc(LocalDateTime value) { return value == null ? null : value.atOffset(ZoneOffset.UTC); }
}
