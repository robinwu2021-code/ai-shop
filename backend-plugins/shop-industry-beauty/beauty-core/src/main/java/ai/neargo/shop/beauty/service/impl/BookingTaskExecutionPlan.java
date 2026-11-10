package ai.neargo.shop.beauty.service.impl;

import ai.neargo.shop.beauty.service.ServiceTaskActionService.PlanKind;
import ai.neargo.shop.beauty.service.ServiceTaskActionService.TaskActionIntentV2;
import ai.neargo.shop.beauty.service.impl.ServiceTaskExecutionServiceImpl.ExecutionOriginal;
import ai.neargo.shop.beauty.service.impl.ServiceTaskExecutionServiceImpl.ExecutionPlan;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.idem.IdempotencyScope;
import ai.neargo.shop.spi.resource.ResourceBookingExecutionEvidencePort.Evidence;
import ai.neargo.shop.spi.resource.ResourceBookingExecutionEvidencePort.Lookup;
import ai.neargo.shop.spi.resource.ResourcePlanContract;
import ai.neargo.shop.spi.resource.ServiceTaskStartAuthorityPort.BookingOriginal;
import ai.neargo.shop.spi.resource.ServiceTaskStartAuthorityPort.SourceGroup;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Source-only exact appointment-to-task projection; Native alone owns allocation generations. */
final class BookingTaskExecutionPlan {
    private BookingTaskExecutionPlan() {}

    record FrozenBooking(BookingOriginal original, List<SourceGroup> groups) {}

    static Lookup lookup(
            AppointmentPersistence db, TaskActionIntentV2 action, ExecutionOriginal original) {
        var plan = plan(db, action, original);
        var evidence = plan.holdEvidence();
        var result =
                new Lookup(
                        action.reference(),
                        evidence.binding(),
                        evidence.hold().holdNo(),
                        evidence.hold().leaseRevision(),
                        plan.appointmentItemNo(),
                        plan.arrangementRevision(),
                        plan.arrangementHash(),
                        plan.bookingPlan(),
                        plan.bookingPlanHash());
        result.validate();
        return result;
    }

    static FrozenBooking freeze(
            AppointmentPersistence db,
            TaskActionIntentV2 action,
            ExecutionOriginal original,
            Evidence observed) {
        require(observed != null);
        observed.validate();
        var expected = lookup(db, action, original);
        require(expected.equals(observed.lookup()));
        var plan = plan(db, action, original);
        var snapshot =
                db.json.readValue(
                        original.task().getExecutionSnapshot(),
                        WorkOrderCoordinator.TaskExecutionSnapshot.class);
        require("ASSIGNED".equals(snapshot.assignmentState()));
        var groups = new ArrayList<SourceGroup>();
        var demands = new ArrayList<ResourcePlanContract.Demand>();
        var qualifications = new ArrayList<ResourcePlanContract.Qualification>();
        int selected = 0;
        for (var rule : snapshot.taskSnapshot().requirements()) {
            var assigned =
                    snapshot.assignments().stream()
                            .filter(
                                    a ->
                                            plan.appointmentItemNo().equals(a.appointmentItemNo())
                                                    && rule.requirementNo()
                                                            .equals(a.requirementNo()))
                            .toList();
            require(assigned.size() == rule.qty());
            for (int ordinal = 0; ordinal < assigned.size(); ordinal++) {
                var a = assigned.get(ordinal);
                boolean staff = "STAFF".equals(rule.resourceType());
                require(staff == (a.personnelNo() != null));
                String group =
                        plan.appointmentItemNo() + ":" + rule.requirementNo() + ":" + ordinal;
                groups.add(
                        new SourceGroup(
                                group,
                                rule.requirementNo(),
                                ordinal,
                                rule.roleCode(),
                                a.resourceNo(),
                                a.personnelNo()));
                demands.add(
                        new ResourcePlanContract.Demand(
                                a.resourceNo(),
                                group,
                                1,
                                List.of(
                                        new ResourcePlanContract.Interval(
                                                a.startAt().toInstant(), a.endAt().toInstant()))));
                qualifications.add(
                        new ResourcePlanContract.Qualification(
                                a.resourceNo(),
                                group,
                                staff ? rule.skillGoodsNo() : null,
                                staff ? snapshot.taskSnapshot().skuNo() : null,
                                staff));
                selected++;
            }
        }
        require(selected == snapshot.assignments().size());
        var task = ResourcePlanContract.decode(plan.taskPlan());
        var demandOrder =
                Comparator.comparing(ResourcePlanContract.Demand::sourceLineRef)
                        .thenComparing(ResourcePlanContract.Demand::resourceNo);
        var qualificationOrder =
                Comparator.comparing(ResourcePlanContract.Qualification::sourceLineRef)
                        .thenComparing(ResourcePlanContract.Qualification::resourceNo);
        require(
                demands.stream()
                                .sorted(demandOrder)
                                .toList()
                                .equals(task.demands().stream().sorted(demandOrder).toList())
                        && qualifications.stream()
                                .sorted(qualificationOrder)
                                .toList()
                                .equals(
                                        task.qualifications().stream()
                                                .sorted(qualificationOrder)
                                                .toList()));
        var selectedGroups =
                groups.stream().map(g -> g.sourceLineRef() + "\n" + g.resourceNo()).toList();
        require(selectedGroups.stream().distinct().count() == groups.size());
        var allocatedGroups =
                observed.allocations().stream()
                        .filter(
                                a ->
                                        selectedGroups.contains(
                                                a.sourceLineRef() + "\n" + a.resourceNo()))
                        .toList();
        require(
                allocatedGroups.stream().noneMatch(a -> a.consumed())
                        && allocatedGroups.stream()
                                        .map(a -> a.sourceLineRef() + "\n" + a.resourceNo())
                                        .distinct()
                                        .count()
                                == groups.size());
        return new FrozenBooking(
                new BookingOriginal(
                        expected.binding().sourceNo(),
                        plan.appointmentItemNo(),
                        plan.arrangementRevision(),
                        plan.arrangementHash(),
                        observed.bookingOwnerNo(),
                        expected.holdNo(),
                        expected.leaseRevision(),
                        observed.allocationRevision(),
                        expected.wholePlanJson(),
                        expected.wholePlanHash()),
                List.copyOf(groups));
    }

    private static ExecutionPlan plan(
            AppointmentPersistence db, TaskActionIntentV2 action, ExecutionOriginal original) {
        require(
                action.initialPlan().kind() == PlanKind.BOOKING
                        && "START_MANUAL_V1".equals(original.command().schema()));
        var plan = db.json.readValue(original.command().intent().frozenPlan(), ExecutionPlan.class);
        require(
                "BEAUTY_EXECUTION_PLAN_V1".equals(plan.schema())
                        && plan.appointmentNo().equals(original.order().getAppointmentNo())
                        && plan.appointmentItemNo().equals(original.task().getAppointmentItemNo())
                        && plan.taskSnapshotHash()
                                .equals(
                                        IdempotencyScope.requestHash(
                                                original.task().getExecutionSnapshot()))
                        && plan.taskPlanHash().equals(ResourcePlanContract.hash(plan.taskPlan()))
                        && plan.bookingPlanHash()
                                .equals(ResourcePlanContract.hash(plan.bookingPlan()))
                        && action.initialPlan().planNo().equals(plan.appointmentNo())
                        && action.initialPlan().planRevision() == plan.arrangementRevision()
                        && action.initialPlan().planHash().equals(plan.arrangementHash())
                        && action.initialPlan().taskPlanHash().equals(plan.taskPlanHash())
                        && plan.holdEvidence() != null
                        && "CONFIRMED".equals(plan.holdEvidence().hold().status())
                        && plan.holdEvidence().binding().sourceNo().equals(plan.appointmentNo())
                        && plan.holdEvidence().hold().planHash().equals(plan.bookingPlanHash())
                        && plan.holdEvidence().arrangementRevision() == plan.arrangementRevision()
                        && plan.holdEvidence().arrangementHash().equals(plan.arrangementHash()));
        return plan;
    }

    private static void require(boolean valid) {
        if (!valid) throw BizException.of(ErrorCode.CONFLICT);
    }
}
