package ai.neargo.shop.merchant.dto;

import ai.neargo.shop.spi.schedule.ScheduleWindowsPort;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** Current store configuration and expanded windows; preserved inactive history is not a live directory. */
public record ScheduleVO(OffsetDateTime from, OffsetDateTime to, Map<String, Long> resourceRevisions,
        List<Rule> rules, List<ScheduleWindowsPort.Window> windows) {
    public ScheduleVO {
        resourceRevisions = Map.copyOf(resourceRevisions);
        rules = List.copyOf(rules);
        windows = List.copyOf(windows);
    }
    /** Immutable output of the active rule configuration in this query range. */
    public record Rule(String ruleNo, String resourceNo, String timezone, int weekdayMask, int startMinute,
            int endMinute, int endDayOffset, LocalDate validFrom, LocalDate validTo, String status) { }
}
