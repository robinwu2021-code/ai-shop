package ai.neargo.shop.beauty.dto;

import java.time.OffsetDateTime;

/** Frozen work-order facts expose no execution or payment permission. */
public record WorkOrderStepVO(String stepNo, String stepCode, int sequenceNo, String status, OffsetDateTime startedAt,
        OffsetDateTime finishedAt) { }
