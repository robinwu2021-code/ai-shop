package ai.neargo.shop.beauty.dto;

import java.time.OffsetDateTime;

/** Stored root facts only: neither payment status nor permission to execute is inferred. */
public record WorkOrderSummaryVO(
        String entityNo,
        String storeNo,
        String workOrderNo,
        String appointmentNo,
        String status,
        long scopeRevision,
        long revision,
        OffsetDateTime createdAt,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        OffsetDateTime closedAt) {}
