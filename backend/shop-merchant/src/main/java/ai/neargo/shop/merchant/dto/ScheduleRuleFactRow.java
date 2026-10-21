package ai.neargo.shop.merchant.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** One rule/employment row retains multiplicity and canonical store identities for cross-store checks. */
public record ScheduleRuleFactRow(String ruleNo, String resourceNo, String storeNo, String requestedStoreNo,
        String timezone, int weekdayMask, int startMinute, int endMinute, int endDayOffset,
        LocalDate validFrom, LocalDate validTo, boolean staff, String assignmentNo,
        LocalDateTime employmentFrom, LocalDateTime employmentTo) {
}
