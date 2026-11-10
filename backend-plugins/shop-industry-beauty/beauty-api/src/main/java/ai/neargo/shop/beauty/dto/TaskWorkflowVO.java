package ai.neargo.shop.beauty.dto;

import java.time.Instant;
import java.util.List;

/**
 * Independent facts: a finished task can still await Core confirmation or manual resource cleaning.
 */
public record TaskWorkflowVO(
        String operationNo,
        String workOrderNo,
        String taskNo,
        String taskStatus,
        long taskRevision,
        String occupationNo,
        long occupationRevision,
        String occupationState,
        boolean fulfilled,
        String stockDisposition,
        String pendingReason,
        List<Segment> segments) {
    public record Segment(
            String segmentNo,
            String resourceNo,
            String personnelNo,
            Instant startedAt,
            Instant endedAt,
            boolean cleaningRequired,
            Instant cleaningCompletedAt) {}
}
