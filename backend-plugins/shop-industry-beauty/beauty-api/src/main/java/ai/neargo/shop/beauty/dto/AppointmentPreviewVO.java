package ai.neargo.shop.beauty.dto;

import java.util.List;
import java.time.OffsetDateTime;

/**
 * A frozen advisory arrangement, consumable once for creation before expiresAt.
 * Quantities expand to sequential service items. startAt/endAt include inter-item buffer and five-minute
 * rounding gaps; assignment intervals additionally expose preparation and cleanup outside the service span.
 * Immutable item times make this padded timeline reviewable before owner reservation.
 */
public record AppointmentPreviewVO(String previewNo, OffsetDateTime expiresAt, String policyVersionNo,
        OffsetDateTime startAt, OffsetDateTime endAt, List<AppointmentPlanItemVO> items,
        List<AppointmentAssignmentVO> assignments, long priceMinor) { }
