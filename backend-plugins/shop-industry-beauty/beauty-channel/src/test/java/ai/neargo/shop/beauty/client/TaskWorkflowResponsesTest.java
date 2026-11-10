package ai.neargo.shop.beauty.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.beauty.dto.TaskWorkflowVO;
import ai.neargo.shop.beauty.spi.BeautyTaskWorkflowInternalApi;
import ai.neargo.shop.spi.security.DelegatedIdentity;
import ai.neargo.shop.spi.security.InternalCall;
import ai.neargo.shop.spi.security.InternalRequestBinding;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

class TaskWorkflowResponsesTest {
    private InternalCall<BeautyTaskWorkflowInternalApi.Query> call() {
        return new InternalCall<>(
                new InternalRequestBinding(
                        1,
                        BeautyTaskWorkflowInternalApi.ENDPOINT,
                        "QUERY",
                        new DelegatedIdentity("MAIN", "MERCHANT", "MCH", "A"),
                        "E",
                        "S",
                        "T"),
                new BeautyTaskWorkflowInternalApi.Query("W", "T"));
    }

    private TaskWorkflowVO value(String workOrder, List<TaskWorkflowVO.Segment> segments) {
        return new TaskWorkflowVO(
                "OP",
                workOrder,
                "T",
                "DONE",
                8,
                "O",
                3,
                "CLEANING",
                true,
                "NOT_REQUIRED",
                null,
                segments);
    }

    @Test
    void exactFactReadAcceptsCleaningWhileForeignParentAndDuplicateSegmentsFail() {
        var now = Instant.parse("2026-11-10T00:00:00Z");
        var segment =
                new TaskWorkflowVO.Segment("SEG", "R", null, now, now.plusSeconds(60), true, null);
        var expected = value("W", List.of(segment));
        assertThat(BeautyReceptionResponses.require(call(), expected)).isSameAs(expected);
        assertThatThrownBy(
                        () ->
                                BeautyReceptionResponses.require(
                                        call(), value("FOREIGN", List.of(segment))))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(
                        () ->
                                BeautyReceptionResponses.require(
                                        call(), value("W", List.of(segment, segment))))
                .isInstanceOf(RuntimeException.class);
    }
}
