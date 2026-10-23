package ai.neargo.shop.beauty.service.impl;

import ai.neargo.shop.beauty.dto.CustomerIdentityVO;
import ai.neargo.shop.beauty.dto.WorkOrderDetailVO;
import ai.neargo.shop.beauty.dto.WorkOrderParticipationVO;
import ai.neargo.shop.beauty.dto.WorkOrderStepVO;
import ai.neargo.shop.beauty.dto.WorkOrderTaskVO;
import ai.neargo.shop.beauty.dto.WorkOrderVO;
import ai.neargo.shop.beauty.entity.BtyWorkOrder;
import ai.neargo.shop.beauty.entity.BtyWorkOrderContext;
import ai.neargo.shop.beauty.mapper.BeautyMappers;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/** Reads authorized stored facts; templates never become execution facts during projection. */
public final class WorkOrderQuery {
    private final AppointmentPersistence db;
    private final BeautyMappers.WorkOrderContextMapper contexts;
    private final BeautyMappers.ServiceTaskMapper tasks;
    private final BeautyMappers.ServiceStepMapper steps;
    private final BeautyMappers.ServiceParticipationMapper participations;
    private final BeautyMappers.ServiceSubmissionMapper submissions;
    private final BeautyMappers.ReceptionOperationMapper operations;
    public WorkOrderQuery(AppointmentPersistence db, BeautyMappers.WorkOrderContextMapper contexts,
            BeautyMappers.ServiceTaskMapper tasks,
            BeautyMappers.ServiceStepMapper steps, BeautyMappers.ServiceParticipationMapper participations,
            BeautyMappers.ServiceSubmissionMapper submissions, BeautyMappers.ReceptionOperationMapper operations) {
        this.db = db; this.contexts = contexts; this.tasks = tasks;
        this.steps = steps; this.participations = participations; this.submissions = submissions;
        this.operations = operations;
    }
    /** The Service has already authorized this persisted root in SQL; callers cannot supply a request number. */
    public WorkOrderDetailVO detail(BtyWorkOrder root) {
        var context = contexts.selectScoped(root.getEntityNo(), root.getStoreNo(), root.getWorkOrderNo());
        boolean legacy = context == null;
        var operation = legacy ? null : operations.selectOpenedForWorkOrder(root.getEntityNo(), root.getStoreNo(),
                root.getWorkOrderNo(), context.getSourceReceptionOperationNo());
        if (!legacy && operation == null) { throw BizException.of(ErrorCode.NOT_FOUND); }
        var rows = tasks.selectForWorkOrder(root.getEntityNo(), root.getStoreNo(), root.getWorkOrderNo());
        var projected = rows.stream().map(task -> {
            WorkOrderCoordinator.TaskExecutionSnapshot execution = null;
            if (!legacy) {
                execution = db.json.readValue(task.getExecutionSnapshot(),
                        WorkOrderCoordinator.TaskExecutionSnapshot.class);
            }
            var taskSteps = steps.selectForTask(root.getEntityNo(), root.getStoreNo(), root.getWorkOrderNo(),
                    task.getTaskNo()).stream().map(s -> new WorkOrderStepVO(s.getStepNo(), s.getStepCode(),
                            s.getSequenceNo(), s.getStatus(), utc(s.getStartedAt()), utc(s.getFinishedAt()))).toList();
            var people = participations.selectForTask(root.getEntityNo(), root.getStoreNo(), root.getWorkOrderNo(),
                    task.getTaskNo()).stream().map(p -> new WorkOrderParticipationVO(p.getParticipationNo(),
                            p.getPersonnelNo(), p.getRoleCode(), utc(p.getStartedAt()), utc(p.getEndedAt()))).toList();
            return new WorkOrderTaskVO(task.getTaskNo(), task.getAppointmentItemNo(), task.getScopeRevision(),
                    task.getSkuNo(), task.getDefinitionVersionNo(), task.getQty(), task.getDoneQty(),
                    task.getVoidedQty(),
                    task.getUom(), task.getStatus(), task.getBindingRevision(),
                    execution == null ? "UNKNOWN" : execution.assignmentState(), legacy ? "UNKNOWN" : "UNBOUND", false,
                    execution == null ? null : execution.plannedStartAt(),
                    execution == null ? null : execution.plannedEndAt(), utc(task.getStartedAt()),
                    execution == null ? List.of() : execution.assignments(),
                    execution == null ? null : execution.taskSnapshot(), taskSteps, people,
                    legacy ? List.of() : List.of("READ"));
        }).toList();
        var submission = submissions.selectOpenForWorkOrder(root.getEntityNo(), root.getStoreNo(),
                root.getWorkOrderNo());
        long original = legacy ? 0 : snapshot(context).originalScopeRevision();
        var vo = new WorkOrderVO(root.getWorkOrderNo(), root.getAppointmentNo(), root.getScopeRevision(),
                root.getStatus(),
                submission == null ? null : submission.getSubmissionNo(), legacy ? "UNKNOWN" : "UNBOUND",
                legacy ? null : operation.getOperationNo(), legacy ? "UNKNOWN" : operation.getPhase(),
                projected.stream().map(WorkOrderTaskVO::taskNo).toList(), projected.isEmpty() ? null
                        : projected.getFirst().plannedStartAt());
        return new WorkOrderDetailVO(vo, new CustomerIdentityVO(legacy ? "UNKNOWN" : context.getIdentityKind(),
                legacy ? null : context.getIdentityRef()), original, projected, legacy ? List.of() : List.of("READ"));
    }
    private WorkOrderCoordinator.WorkOrderScopeSnapshot snapshot(BtyWorkOrderContext context) {
        return db.json.readValue(context.getFrozenScope(), WorkOrderCoordinator.WorkOrderScopeSnapshot.class);
    }
    private static OffsetDateTime utc(LocalDateTime value) { return value == null ? null : value.atOffset(ZoneOffset.UTC); }
}
