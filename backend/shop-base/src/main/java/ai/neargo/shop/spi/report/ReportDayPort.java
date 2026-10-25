package ai.neargo.shop.spi.report;

import java.time.LocalDate;
import ai.neargo.shop.spi.report.ReportContracts.DayEvidence;
import ai.neargo.shop.spi.report.ReportContracts.Scope;

/** Reads original persisted operating-day evidence; never creates or closes a business day. */
public interface ReportDayPort {
    record OperatingDay(LocalDate businessDate,DayEvidence evidence) {}
    OperatingDay current(Scope scope);
    DayEvidence day(Scope scope, LocalDate businessDate);
}
