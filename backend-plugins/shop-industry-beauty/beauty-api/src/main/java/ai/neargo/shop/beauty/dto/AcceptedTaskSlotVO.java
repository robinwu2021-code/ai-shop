package ai.neargo.shop.beauty.dto;

import java.time.Instant;

/**
 * Published revisions identify historical acceptance without promising future owner qualification.
 */
public record AcceptedTaskSlotVO(
        String assignmentNo,
        String requirementNo,
        int ordinal,
        String roleCode,
        long assignmentRevision,
        Instant validUntil,
        String currentState) {}
