package ai.neargo.shop.beauty.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.neargo.shop.beauty.dto.AppointmentAssignmentVO;
import ai.neargo.shop.beauty.dto.WorkOrderRequirementVO;
import ai.neargo.shop.beauty.dto.WorkOrderTaskSnapshotVO;
import ai.neargo.shop.beauty.service.ServiceTaskActionService.AcceptedSlotSelection;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.spi.personnel.PersonnelOperatorFactsPort.OperatorPersonnelFact;
import ai.neargo.shop.spi.resource.ResourcePlanContract;
import ai.neargo.shop.spi.resource.ServiceTaskStartAuthorityPort.AcceptedStaffRef;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * SOURCE ONLY / NOT_RUN. Ordinal order must come from frozen demand identity, never alphabetic
 * sort.
 */
class TaskActionPolicyTest {
    @Test
    void bookingOrdinalsRetainOriginalListAndIgnoreNonStaff() {
        var start = OffsetDateTime.parse("2026-10-30T10:00:00Z");
        var definition =
                new WorkOrderTaskSnapshotVO(
                        1,
                        "G",
                        "SKU",
                        "DV",
                        "hash",
                        30,
                        0,
                        0,
                        List.of(
                                new WorkOrderRequirementVO(
                                        "R", "ASSIST", "STAFF", "G", 2, 0, 0, 30, "ANY_QUALIFIED"),
                                new WorkOrderRequirementVO(
                                        "ROOM", "ASSIST", "ROOM", null, 1, 1, 0, 30, "ANY")),
                        List.of(),
                        List.of());
        var slots =
                TaskActionPolicy.bookingSlots(
                        "ITEM",
                        definition,
                        List.of(
                                new AppointmentAssignmentVO(
                                        "ITEM",
                                        "R",
                                        "ZPERSON",
                                        "ZRESOURCE",
                                        start,
                                        start.plusMinutes(30)),
                                new AppointmentAssignmentVO(
                                        "ITEM",
                                        "R",
                                        "APERSON",
                                        "ARESOURCE",
                                        start,
                                        start.plusMinutes(30)),
                                new AppointmentAssignmentVO(
                                        "ITEM",
                                        "ROOM",
                                        null,
                                        "ROOM",
                                        start,
                                        start.plusMinutes(30))));
        assertEquals(2, slots.size());
        assertEquals("ZPERSON", slots.getFirst().personnelNo());
        assertEquals("ITEM:R:1", slots.get(1).sourceLineRef());
        var second = TaskActionPolicy.requirePlannedSelf(slots, person("APERSON"));
        var plan =
                ResourcePlanContract.decode(
                        ResourcePlanContract.encode(
                                List.of(
                                        new ResourcePlanContract.Demand(
                                                "ARESOURCE",
                                                "ITEM:R:1",
                                                1,
                                                List.of(
                                                        new ResourcePlanContract.Interval(
                                                                start.toInstant(),
                                                                start.plusMinutes(30)
                                                                        .toInstant())))),
                                List.of(
                                        new ResourcePlanContract.Qualification(
                                                "ARESOURCE", "ITEM:R:1", "G", "SKU", true))));
        TaskActionPolicy.resolveInitialSlot(second, definition, plan);
        assertThrows(
                BizException.class,
                () -> TaskActionPolicy.requirePlannedSelf(slots, person("OTHER")));
        assertThrows(
                BizException.class,
                () ->
                        TaskActionPolicy.requirePlannedSelf(
                                List.of(second, second), person("APERSON")));
    }

    private OperatorPersonnelFact person(String number) {
        return new OperatorPersonnelFact(
                number, "EMPLOYMENT", 1, 1, 1, Instant.parse("2026-01-01T00:00:00Z"), null);
    }

    @Test
    void v2OwnSlotsAreNonemptyExplicitSubsetWhileRosterStaysComplete() {
        var at = Instant.parse("2026-10-30T00:00:00Z");
        var person = new OperatorPersonnelFact("person", "employment", 1, 1, 1, at, null);
        var early =
                new AcceptedStaffRef(
                        "A1",
                        1,
                        "EARLY",
                        0,
                        "MASTER",
                        "R",
                        "person",
                        "AC1",
                        "a".repeat(64),
                        "b".repeat(64),
                        null,
                        "M",
                        person);
        var late =
                new AcceptedStaffRef(
                        "A2",
                        2,
                        "LATE",
                        0,
                        "ASSISTANT",
                        "R",
                        "person",
                        "AC2",
                        "c".repeat(64),
                        "d".repeat(64),
                        null,
                        "M",
                        person);
        var selected = new AcceptedSlotSelection("EARLY", 0, 1);
        Assertions.assertDoesNotThrow(
                () ->
                        TaskActionPolicy.requireOwnSelections(
                                List.of(selected), List.of(early, late), person));
        Assertions.assertThrows(
                RuntimeException.class,
                () ->
                        TaskActionPolicy.requireOwnSelections(
                                List.of(), List.of(early, late), person));
        Assertions.assertThrows(
                RuntimeException.class,
                () ->
                        TaskActionPolicy.requireOwnSelections(
                                List.of(selected, selected), List.of(early, late), person));
        var stale = new AcceptedSlotSelection("LATE", 0, 1);
        Assertions.assertThrows(
                RuntimeException.class,
                () ->
                        TaskActionPolicy.requireOwnSelections(
                                List.of(stale), List.of(early, late), person));
    }
}
