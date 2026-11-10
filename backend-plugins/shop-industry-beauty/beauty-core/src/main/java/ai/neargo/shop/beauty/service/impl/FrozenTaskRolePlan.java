package ai.neargo.shop.beauty.service.impl;

import ai.neargo.shop.beauty.dto.WorkOrderTaskSnapshotVO;
import ai.neargo.shop.beauty.policy.TaskActionPolicy;
import ai.neargo.shop.beauty.service.ServiceTaskActionService.InitialPlan;
import ai.neargo.shop.beauty.service.ServiceTaskActionService.PlanKind;
import ai.neargo.shop.beauty.service.ServiceTaskActionService.PlannedRoleSlot;
import ai.neargo.shop.beauty.service.impl.ServiceTaskExecutionServiceImpl.ExecutionOriginal;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.idem.IdempotencyScope;
import ai.neargo.shop.spi.resource.ResourcePlanContract;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One local frozen-plan interpretation shared by action, acceptance and safe roster projection. No
 * owner IO.
 */
final class FrozenTaskRolePlan {
    private FrozenTaskRolePlan() {}

    record Planned(
            WorkOrderTaskSnapshotVO definition,
            List<PlannedRoleSlot> slots,
            InitialPlan initial,
            String taskPlan) {}

    static Planned read(AppointmentPersistence db, ExecutionOriginal o) {
        var t = o.task();
        var command = o.command();
        if ("START_DIRECT_V2".equals(command.schema())) {
            var envelope = DirectTaskPlan.ExecutionPlan.decode(command.intent().frozenPlan());
            var plan = DirectTaskPlan.decode(envelope.directPlan());
            require(
                    plan.planNo().equals(t.getDirectPlanNo())
                            && plan.planRevision() == t.getDirectPlanRevision()
                            && plan.hash().equals(t.getDirectPlanHash()));
            var slots = directSlots(plan, t.getTaskNo());
            return new Planned(
                    plan.definition(),
                    List.copyOf(slots),
                    new InitialPlan(
                            PlanKind.DIRECT,
                            plan.planNo(),
                            plan.planRevision(),
                            plan.hash(),
                            envelope.taskPlanHash()),
                    envelope.taskPlan());
        }
        var envelope =
                db.json.readValue(
                        command.intent().frozenPlan(),
                        ServiceTaskExecutionServiceImpl.ExecutionPlan.class);
        require(
                "BEAUTY_EXECUTION_PLAN_V1".equals(envelope.schema())
                        && Objects.equals(o.order().getAppointmentNo(), envelope.appointmentNo())
                        && Objects.equals(t.getAppointmentItemNo(), envelope.appointmentItemNo())
                        && envelope.taskSnapshotHash()
                                .equals(IdempotencyScope.requestHash(t.getExecutionSnapshot()))
                        && ResourcePlanContract.hash(envelope.taskPlan())
                                .equals(envelope.taskPlanHash()));
        var snapshot =
                db.json.readValue(
                        t.getExecutionSnapshot(), WorkOrderCoordinator.TaskExecutionSnapshot.class);
        var slots =
                TaskActionPolicy.bookingSlots(
                        t.getAppointmentItemNo(), snapshot.taskSnapshot(), snapshot.assignments());
        return new Planned(
                snapshot.taskSnapshot(),
                slots,
                new InitialPlan(
                        PlanKind.BOOKING,
                        envelope.appointmentNo(),
                        envelope.arrangementRevision(),
                        envelope.arrangementHash(),
                        envelope.taskPlanHash()),
                envelope.taskPlan());
    }

    static List<PlannedRoleSlot> directSlots(DirectTaskPlan plan, String taskNo) {
        var slots = new ArrayList<PlannedRoleSlot>();
        var resource = ResourcePlanContract.decode(plan.taskPlan());
        for (var rule : plan.definition().requirements()) {
            if (!"STAFF".equals(rule.resourceType())) {
                continue;
            }
            for (var a : plan.assignments()) {
                if (!rule.requirementNo().equals(a.requirementNo())) {
                    continue;
                }
                String group = taskNo + ":" + a.requirementNo() + ":" + a.ordinal();
                var demand =
                        resource.demands().stream()
                                .filter(
                                        d ->
                                                group.equals(d.sourceLineRef())
                                                        && a.resourceNo().equals(d.resourceNo()))
                                .findFirst()
                                .orElseThrow();
                var interval = demand.intervals().getFirst();
                slots.add(
                        new PlannedRoleSlot(
                                a.requirementNo(),
                                a.ordinal(),
                                rule.roleCode(),
                                group,
                                a.personnelNo(),
                                a.resourceNo(),
                                interval.startAt(),
                                interval.endAt()));
            }
        }
        return List.copyOf(slots);
    }

    private static void require(boolean value) {
        if (!value) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }
}
