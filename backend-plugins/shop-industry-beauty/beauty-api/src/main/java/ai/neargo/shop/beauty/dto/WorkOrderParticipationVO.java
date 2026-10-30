package ai.neargo.shop.beauty.dto;

import java.time.OffsetDateTime;

/** Frozen work-order facts expose no execution or payment permission. */
public record WorkOrderParticipationVO(String participationNo, String personnelNo, String roleCode,
        OffsetDateTime startedAt, OffsetDateTime endedAt) { }
