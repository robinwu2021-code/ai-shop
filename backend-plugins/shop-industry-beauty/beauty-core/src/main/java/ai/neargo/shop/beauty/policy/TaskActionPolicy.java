package ai.neargo.shop.beauty.policy;

import ai.neargo.shop.beauty.dto.AppointmentAssignmentVO;
import ai.neargo.shop.beauty.dto.WorkOrderTaskSnapshotVO;
import ai.neargo.shop.beauty.service.ServiceTaskActionService.AcceptedSlotSelection;
import ai.neargo.shop.beauty.service.ServiceTaskActionService.InitialPlan;
import ai.neargo.shop.beauty.service.ServiceTaskActionService.PlannedRoleSlot;
import ai.neargo.shop.beauty.service.ServiceTaskActionService.TaskActionIntent;
import ai.neargo.shop.beauty.service.ServiceTaskActionService.TaskActionVersions;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.personnel.PersonnelOperatorFactsPort.OperatorPersonnelFact;
import ai.neargo.shop.spi.resource.ResourcePlanContract.FrozenPlan;
import ai.neargo.shop.spi.resource.ServiceTaskStartAuthorityPort.AcceptedStaffRef;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Pure rules over the immutable definition/roster and current owner facts. No identity or owner IO.
 */
public final class TaskActionPolicy {
    private TaskActionPolicy() {}

    /**
     * BOOKING ordinals follow the original same-item/requirement list; never resource/person
     * sorting.
     */
    public static List<PlannedRoleSlot> bookingSlots(
            String item,
            WorkOrderTaskSnapshotVO definition,
            List<AppointmentAssignmentVO> assignments) {
        var slots = new ArrayList<PlannedRoleSlot>();
        for (var rule : definition.requirements()) {
            if (!"STAFF".equals(rule.resourceType())) continue;
            var group =
                    assignments.stream()
                            .filter(
                                    a ->
                                            item.equals(a.appointmentItemNo())
                                                    && rule.requirementNo()
                                                            .equals(a.requirementNo()))
                            .toList();
            require(group.size() == rule.qty());
            for (int ordinal = 0; ordinal < group.size(); ordinal++) {
                var a = group.get(ordinal);
                slots.add(
                        new PlannedRoleSlot(
                                rule.requirementNo(),
                                ordinal,
                                rule.roleCode(),
                                item + ":" + rule.requirementNo() + ":" + ordinal,
                                a.personnelNo(),
                                a.resourceNo(),
                                a.startAt().toInstant(),
                                a.endAt().toInstant()));
            }
        }
        return List.copyOf(slots);
    }

    public static PlannedRoleSlot requirePlannedSelf(
            List<PlannedRoleSlot> slots, OperatorPersonnelFact person) {
        var own = slots.stream().filter(s -> person.personnelNo().equals(s.personnelNo())).toList();
        if (own.isEmpty()) throw BizException.of(ErrorCode.FORBIDDEN);
        if (own.size() != 1)
            throw BizException.of(
                    ErrorCode.CHECKOUT_UNSUPPORTED_SELECTION, "TASK_ROLE_SELECTION_REQUIRED");
        return own.getFirst();
    }

    /**
     * An exact STAFF slot must have its own quantity-one demand and required project qualification.
     */
    public static void resolveInitialSlot(
            PlannedRoleSlot slot, WorkOrderTaskSnapshotVO definition, FrozenPlan plan) {
        var rules =
                definition.requirements().stream()
                        .filter(
                                r ->
                                        r.requirementNo().equals(slot.requirementNo())
                                                && "STAFF".equals(r.resourceType())
                                                && Objects.equals(r.roleCode(), slot.roleCode()))
                        .toList();
        require(
                rules.size() == 1
                        && slot.ordinal() >= 0
                        && slot.ordinal() < rules.getFirst().qty());
        var demands =
                plan.demands().stream()
                        .filter(
                                d ->
                                        d.sourceLineRef().equals(slot.sourceLineRef())
                                                && d.resourceNo().equals(slot.resourceNo()))
                        .toList();
        require(
                demands.size() == 1
                        && demands.getFirst().quantity() == 1
                        && demands.getFirst().intervals().size() == 1
                        && demands.getFirst()
                                .intervals()
                                .getFirst()
                                .startAt()
                                .equals(slot.startAt())
                        && demands.getFirst().intervals().getFirst().endAt().equals(slot.endAt()));
        require(
                plan.qualifications().stream()
                        .anyMatch(
                                q ->
                                        q.sourceLineRef().equals(slot.sourceLineRef())
                                                && q.resourceNo().equals(slot.resourceNo())
                                                && q.required()
                                                && Objects.equals(
                                                        q.goodsNo(),
                                                        rules.getFirst().skillGoodsNo())
                                                && Objects.equals(q.skuNo(), definition.skuNo())));
    }

    public static boolean compareBusinessVersions(
            TaskActionIntent intent,
            TaskActionVersions versions,
            OperatorPersonnelFact personnel,
            PlannedRoleSlot slot,
            InitialPlan plan) {
        return intent.expected().equals(versions)
                && intent.currentPersonnel().equals(personnel)
                && intent.slot().equals(slot)
                && intent.initialPlan().equals(plan);
    }

    /**
     * START activates only the explicitly selected SELF subset; Native still protects the complete
     * roster.
     */
    public static void requireOwnSelections(
            List<AcceptedSlotSelection> choices,
            List<AcceptedStaffRef> roster,
            OperatorPersonnelFact person) {
        require(
                !choices.isEmpty()
                        && choices.size() <= 100
                        && choices.stream()
                                        .map(s -> s.requirementNo() + ":" + s.ordinal())
                                        .distinct()
                                        .count()
                                == choices.size());
        for (var choice : choices) {
            var found =
                    roster.stream()
                            .filter(
                                    s ->
                                            s.requirementNo().equals(choice.requirementNo())
                                                    && s.ordinal() == choice.ordinal())
                            .toList();
            require(
                    found.size() == 1
                            && found.getFirst().assignmentRevision()
                                    == choice.assignmentRevision());
            if (!found.getFirst().personnelNo().equals(person.personnelNo()))
                throw BizException.of(ErrorCode.FORBIDDEN);
            require(found.getFirst().personnel().equals(person));
        }
    }

    private static void require(boolean value) {
        if (!value) throw BizException.of(ErrorCode.CONFLICT);
    }
}
