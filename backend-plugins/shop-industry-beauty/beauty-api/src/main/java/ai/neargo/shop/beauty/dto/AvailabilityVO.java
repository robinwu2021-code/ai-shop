package ai.neargo.shop.beauty.dto;

import java.util.List;
import java.time.OffsetDateTime;

/**
 * Advisory search over at most 48 slots and 50 resource candidates per slot.
 * complete=false means exhaustion cannot establish unavailability; only owner acceptance reserves capacity.
 */
public record AvailabilityVO(List<AppointmentPreviewVO> candidates, OffsetDateTime expiresAt, boolean complete) { }
