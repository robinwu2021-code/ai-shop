package ai.neargo.shop.merchant.dto;

import java.time.LocalDateTime;

/** Owner-side SQL projection keeps persistence rows out of the public shared contract. */
public record PersonnelAssignmentFactRow(String assignmentNo, String personnelNo, String displayName,
        String levelCode, boolean publicVisible, int sortOrder, LocalDateTime validFrom, LocalDateTime validTo) {
}
