package ai.neargo.shop.beauty.dto;

import java.time.Instant;
import java.util.List;

/**
 * Independent acceptance is a historical fact; this result never authorizes or starts execution.
 */
public record TaskActionResultVO(
        String operationNo,
        String taskNo,
        String action,
        String outcome,
        Instant acceptedAt,
        List<AcceptedTaskSlotVO> slots,
        String pendingReason,
        boolean canStart) {
    public TaskActionResultVO {
        slots = List.copyOf(slots);
    }
}
