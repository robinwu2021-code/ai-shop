package ai.neargo.shop.report.dto;

import java.time.Instant;
import java.time.LocalDate;

import ai.neargo.shop.spi.report.ReportContracts.BuildReq;
import ai.neargo.shop.spi.report.ReportContracts.BuildStatus;
import ai.neargo.shop.spi.report.ReportContracts.Partition;

/** JDBC projection records: no business Entity inheritance or default database mapper. */
public final class ReportRows {
    private ReportRows() {}
    public record CursorRow(Partition partition, LocalDate firstDate, LocalDate lastPlannedDate,
            boolean enabled, boolean defaultForReads, long version) {}
    public record BuildRow(BuildReq request, String buildNo, String requestHash, long attempt,
            long fence, long expectedGeneration, String leaseOwner, Instant leaseUntil,
            BuildStatus status, String validationHash, Instant finishedAt) {}
    public record PublicationRow(Partition partition, String buildNo, Long attempt, long generation,
            long fence, String leaseOwner, Instant leaseUntil, Instant publishedAt, Instant databaseNow) {}
    public record DirtyRow(Partition partition, long generation, long acked, Instant readyAfter) {}
}
