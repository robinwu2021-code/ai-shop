package ai.neargo.shop.report.dto;

import java.time.Instant;
import java.util.List;
import ai.neargo.shop.spi.report.ReportExportPort.Reference;
import ai.neargo.shop.spi.report.ReportExportPort.Status;

/** Snapshot includes the fixed result as well as its criteria, so later publication cannot alter a file. */
public record ReportExportTaskRow(Reference reference,String requestKey,String requestHash,String snapshot,
        String snapshotHash,Status status,long attempt,String leaseOwner,Instant leaseUntil,String objectRef,
        List<String> retiredObjects,String checksum,Long rowCount,Instant expiresAt,Instant databaseNow) {
    public ReportExportTaskRow { retiredObjects=List.copyOf(retiredObjects); }
}
