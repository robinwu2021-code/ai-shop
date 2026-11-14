package ai.neargo.shop.merchant.dto;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Set;

/** Explicit finite validity lets maintenance validate every occurrence instead of approving an unchecked future. */
public record SaveScheduleRuleReq(String ruleNo, String resourceNo, Long expectedRevision, String timezone,
        Integer weekdayMask, Integer startMinute, Integer endMinute, Integer endDayOffset,
        LocalDate validFrom, LocalDate validTo, String status, String reason) {
    public SaveScheduleRuleReq {
        PersonnelInputs.text(ruleNo, 32, true);
        PersonnelInputs.text(resourceNo, 32, false);
        PersonnelInputs.revision(expectedRevision);
        PersonnelInputs.text(timezone, 64, false);
        try { ZoneId.of(timezone); }
        catch (DateTimeException invalid) { PersonnelInputs.require(false); }
        PersonnelInputs.require(weekdayMask != null && weekdayMask >= 1 && weekdayMask <= 127);
        PersonnelInputs.require(startMinute != null && startMinute >= 0 && startMinute <= 1439);
        PersonnelInputs.require(endMinute != null && endMinute >= 0 && endMinute <= 1440);
        PersonnelInputs.require(endDayOffset != null && (endDayOffset == 0 || endDayOffset == 1));
        int duration = endDayOffset * 1440 + endMinute - startMinute;
        PersonnelInputs.require(duration >= 1 && duration <= 1440);
        PersonnelInputs.require(validFrom != null && validTo != null && !validTo.isBefore(validFrom)
                && !validFrom.isBefore(LocalDate.of(1000, 1, 5))
                && !validTo.isAfter(LocalDate.of(9999, 12, 25))
                && ChronoUnit.DAYS.between(validFrom, validTo) <= 365);
        PersonnelInputs.require(status != null && Set.of("ACTIVE", "INACTIVE").contains(status));
        PersonnelInputs.require(ruleNo != null || "ACTIVE".equals(status));
        PersonnelInputs.text(reason, 500, false);
    }
}
