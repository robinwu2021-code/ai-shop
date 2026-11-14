package ai.neargo.shop.merchant.dto;

import java.time.LocalDateTime;

/**
 * Owner-local link identity permits consistency checks without exposing the account or link on the
 * wire.
 */
public record OperatorPersonnelFactRow(
        String personnelNo,
        String assignmentNo,
        long personnelRevision,
        long assignmentRevision,
        long linkRevision,
        LocalDateTime validFrom,
        LocalDateTime validTo,
        String linkNo) {}
