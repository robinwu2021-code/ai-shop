package ai.neargo.shop.beauty.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.beauty.dto.AssignServiceTaskReq;
import ai.neargo.shop.beauty.dto.WorkOrderRequirementVO;
import ai.neargo.shop.beauty.dto.WorkOrderTaskSnapshotVO;
import ai.neargo.shop.idem.IdempotencyScope;
import ai.neargo.shop.spi.resource.ResourcePlanContract;
import ai.neargo.shop.spi.trade.ServiceExecutionContract;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionScope;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

/** SOURCE only: exact task group, role slot and strict decoder regressions are observable here. */
class DirectTaskPlanTest {
    private final Instant start = Instant.parse("2026-11-01T10:00:00Z");

    private WorkOrderTaskSnapshotVO definition() {
        return new WorkOrderTaskSnapshotVO(
                1,
                "G",
                "SKU",
                "DV",
                "a".repeat(64),
                30,
                5,
                10,
                List.of(
                        new WorkOrderRequirementVO(
                                "REQ", "MAIN", "STAFF", "G", 1, 0, 0, 30, "ANY_QUALIFIED")),
                List.of(),
                List.of());
    }

    @Test
    void derivesRealTaskGroupsAndBufferedIntervalsFromFrozenDefinition() {
        var plan =
                DirectTaskPlan.resourcePlan(
                        "TASK",
                        definition(),
                        start,
                        List.of(new AssignServiceTaskReq.Selection("REQ", 0, "RES", "PERSON")));
        var decoded = ResourcePlanContract.decode(plan);
        assertThat(decoded.demands())
                .containsExactly(
                        new ResourcePlanContract.Demand(
                                "RES",
                                "TASK:REQ:0",
                                1,
                                List.of(
                                        new ResourcePlanContract.Interval(
                                                start.minusSeconds(300),
                                                start.plusSeconds(2400)))));
        assertThat(decoded.qualifications())
                .containsExactly(
                        new ResourcePlanContract.Qualification(
                                "RES", "TASK:REQ:0", "G", "SKU", true));
    }

    @Test
    void refusesPartialExtraDuplicateAndWrongRoleSelections() {
        for (var choices :
                List.of(
                        List.<AssignServiceTaskReq.Selection>of(),
                        List.of(new AssignServiceTaskReq.Selection("OTHER", 0, "RES", "PERSON")),
                        List.of(new AssignServiceTaskReq.Selection("REQ", 1, "RES", "PERSON")),
                        List.of(new AssignServiceTaskReq.Selection("REQ", 0, "RES", null)),
                        List.of(
                                new AssignServiceTaskReq.Selection("REQ", 0, "RES", "PERSON"),
                                new AssignServiceTaskReq.Selection("REQ", 0, "RES", "PERSON")))) {
            assertThatThrownBy(
                            () -> DirectTaskPlan.resourcePlan("TASK", definition(), start, choices))
                    .isInstanceOf(RuntimeException.class);
        }
    }

    @Test
    void legacyAppointmentPlanCanonicalBytesAndDigestRemainUnchanged() throws Exception {
        var legacy =
                new ServiceTaskExecutionServiceImpl.ExecutionPlan(
                        "BEAUTY_EXECUTION_PLAN_V1",
                        "A",
                        "I",
                        1,
                        "a".repeat(64),
                        null,
                        "{}",
                        "b".repeat(64),
                        "c".repeat(64),
                        "{}",
                        "d".repeat(64));
        String canonical = ServiceExecutionContract.canonical(legacy);
        assertThat(canonical)
                .isEqualTo(
                        "{\"appointmentItemNo\":\"I\",\"appointmentNo\":\"A\","
                            + "\"arrangementHash\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\","
                            + "\"arrangementRevision\":1,\"bookingPlan\":\"{}\","
                            + "\"bookingPlanHash\":\"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\","
                            + "\"holdEvidence\":null,\"schema\":\"BEAUTY_EXECUTION_PLAN_V1\",\"taskPlan\":\"{}\","
                            + "\"taskPlanHash\":\"dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd\","
                            + "\"taskSnapshotHash\":\"cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc\"}");
        assertThat(
                        HexFormat.of()
                                .formatHex(
                                        MessageDigest.getInstance("SHA-256")
                                                .digest(
                                                        ("BEAUTY_EXECUTION_PLAN_V1\n" + canonical)
                                                                .getBytes(StandardCharsets.UTF_8))))
                .isEqualTo("69928311e14b09e190217d12eb60d66f8620f53a342a8d5456344a292d4e38c4");
    }

    @Test
    void typedAssignmentRequestRetainsOriginalCanonicalFieldsAndRequestHash() {
        var request =
                new AssignServiceTaskReq(
                        0,
                        1,
                        1,
                        1,
                        start,
                        List.of(new AssignServiceTaskReq.Selection("REQ", 0, "RES", "PERSON")));
        String canonical = ServiceExecutionContract.canonical(request);
        assertThat(canonical)
                .isEqualTo(
                        "{\"assignments\":[{\"ordinal\":0,\"personnelNo\":\"PERSON\","
                            + "\"requirementNo\":\"REQ\",\"resourceNo\":\"RES\"}],\"expectedBindingRevision\":1,"
                            + "\"expectedScopeRevision\":1,\"expectedSourceRevision\":1,\"expectedTaskRevision\":0,"
                            + "\"startAt\":\"2026-11-01T10:00:00Z\"}");
        assertThat(
                        IdempotencyScope.requestHash(
                                "BEAUTY_DIRECT_REQUEST_V1",
                                "a".repeat(64),
                                "WO",
                                "TASK",
                                canonical))
                .isEqualTo("e3dcb1d3ce1c159aed231dd42f5e12c8c32d0bb374c5d96aac08cc66488b4066");
    }

    @Test
    void strictDirectPlanRejectsUnknownVersionMissingFieldsAndWrongHash() {
        var selections = List.of(new AssignServiceTaskReq.Selection("REQ", 0, "RES", "PERSON"));
        String resourcePlan = DirectTaskPlan.resourcePlan("TASK", definition(), start, selections);
        var plan =
                new DirectTaskPlan(
                        "BEAUTY_DIRECT_PLAN_V1",
                        new ExecutionScope("MAIN", "E", "S"),
                        "WO",
                        "TASK",
                        "PLAN",
                        1,
                        1,
                        1,
                        "BIND",
                        1,
                        0,
                        "SOURCE",
                        "LINE",
                        1,
                        "b".repeat(64),
                        "c".repeat(64),
                        definition(),
                        start,
                        start.plusSeconds(1800),
                        selections,
                        resourcePlan,
                        ResourcePlanContract.hash(resourcePlan));
        String valid = plan.canonical();
        assertThat(DirectTaskPlan.decode(valid)).isEqualTo(plan);
        for (String bytes :
                List.of(
                        valid.replace("BEAUTY_DIRECT_PLAN_V1", "BEAUTY_DIRECT_PLAN_V9"),
                        valid.replace("\"planRevision\":1", "\"planRevision\":0"),
                        valid.replace("\"planNo\":\"PLAN\",", ""),
                        valid.replace("\"planNo\":\"PLAN\"", "\"extra\":true,\"planNo\":\"PLAN\""),
                        valid.replace(plan.taskPlanHash(), "d".repeat(64)))) {
            assertThatThrownBy(() -> DirectTaskPlan.decode(bytes))
                    .isInstanceOf(RuntimeException.class);
        }
    }
}
