package ai.neargo.shop.spi.report;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import ai.neargo.shop.spi.report.ReportContracts.Scope;

/** Original industry binding resolves correction dates; the consumer must not guess from today's date. */
public interface ReportAffectedDaysPort {
    record Change(String tenantNo,String orderNo,String subOrderNo,Instant occurredAt) {
        public Change {
            ReportContracts.identifier(tenantNo,64); Objects.requireNonNull(occurredAt);
            if(orderNo==null && subOrderNo==null) throw new IllegalArgumentException("REPORT_CHANGE_REFERENCE_REQUIRED");
            if(orderNo!=null) ReportContracts.identifier(orderNo,64);
            if(subOrderNo!=null) ReportContracts.identifier(subOrderNo,64);
        }
    }
    record AffectedDay(Scope scope,LocalDate businessDate) {
        public AffectedDay { Objects.requireNonNull(scope);Objects.requireNonNull(businessDate); }
    }
    List<AffectedDay> affectedDays(Change change);
}
