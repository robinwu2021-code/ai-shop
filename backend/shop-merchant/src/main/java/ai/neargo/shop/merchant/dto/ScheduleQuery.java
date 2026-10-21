package ai.neargo.shop.merchant.dto;

import ai.neargo.shop.spi.schedule.ScheduleWindowsPort;
import java.time.OffsetDateTime;
import java.util.Set;

/** Bounded working-window query, never a capacity availability claim. */
public record ScheduleQuery(Set<String> resourceNos, OffsetDateTime from, OffsetDateTime to) {
    public ScheduleQuery {
        PersonnelInputs.require(resourceNos != null && !resourceNos.isEmpty() && resourceNos.size() <= 50);
        resourceNos.forEach(no -> PersonnelInputs.text(no, 32, false));
        resourceNos = Set.copyOf(resourceNos);
        from = PersonnelInputs.utc(from, false);
        to = PersonnelInputs.utc(to, false);
        new ScheduleWindowsPort.Query("MAIN", "SCOPE", "STORE", resourceNos, from.toInstant(), to.toInstant());
    }
}
