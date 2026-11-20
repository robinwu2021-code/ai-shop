package ai.neargo.shop.reportbridge.port;

import ai.neargo.shop.spi.report.ReportDayPort;
import ai.neargo.shop.spi.report.ReportContracts.DayEvidence;
import ai.neargo.shop.spi.report.ReportContracts.Scope;
import java.time.LocalDate;
import org.springframework.beans.factory.ObjectProvider;

public final class DeferredReportDayPort implements ReportDayPort {
    private final ObjectProvider<ReportDayPort> owners;
    public DeferredReportDayPort(ObjectProvider<ReportDayPort> owners) {this.owners=owners;}
    private ReportDayPort owner() {
        var owner=owners.getIfUnique();if(owner==null) throw new IllegalStateException("REPORT_DAY_OWNER_NOT_AVAILABLE");return owner;
    }
    public DayEvidence day(Scope scope,LocalDate date) {return owner().day(scope,date);}
    public OperatingDay current(Scope scope) {return owner().current(scope);}
}
