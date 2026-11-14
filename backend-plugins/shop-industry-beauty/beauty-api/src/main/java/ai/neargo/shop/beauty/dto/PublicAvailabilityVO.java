package ai.neargo.shop.beauty.dto;

import java.util.List;
import java.time.OffsetDateTime;

/**
 * Public advisory search has the same 48-slot and 50-resource bounds as authenticated availability.
 * complete=false distinguishes bounded results from a definitive search; no capacity is reserved.
 */
public record PublicAvailabilityVO(List<AppointmentPreviewVO> candidates, OffsetDateTime expiresAt, boolean
        complete) { }
