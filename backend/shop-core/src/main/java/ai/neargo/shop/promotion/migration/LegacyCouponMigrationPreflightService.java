package ai.neargo.shop.promotion.migration;

import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.SourceSystem;
import java.time.Instant;
import java.util.List;

/** Read-only inventory precedes source fencing and import; this service cannot authorize a cutover. */
public interface LegacyCouponMigrationPreflightService {
    /** Complete source-table counts, separate from the visible-instance compatibility sample. */
    InventoryReport inventory(EntitlementContext context,SourceSystem source,long afterId,int limit);
    record TemplateInventory(SourceSystem sourceSystem,String templateNo,String entityNo,
                             boolean templateDeleted,boolean templateArchived,String migrationState,
                             long instanceCount,long deletedInstanceCount,long issueCount,long deletedIssueCount,
                             Long historyCount,Long deletedHistoryCount,boolean peerTemplatePresent,List<String> blockers) {
        public TemplateInventory { blockers=List.copyOf(blockers); }
    }
    record InventoryReport(Instant capturedAt,List<TemplateInventory> records,long nextId,
                           long orphanInstances,long orphanIssues,Long orphanHistory) {
        public InventoryReport { records=List.copyOf(records); }
    }
    Report scan(EntitlementContext context, SourceSystem source, long afterId, int limit);
    public record Line(SourceSystem sourceSystem, String sourceNo, String templateNo, String userNo,
                       String entityNo, long sourceRevision, Long templateRevision, String sourceStatus,
                       int totalUnits, int usedUnits, long remainingUnits, Instant exclusiveUntil,
                       long historicalApplications, boolean hasLegacyCode, List<String> blockers) {
        public Line { blockers = List.copyOf(blockers); }
    }
    public record Report(Instant capturedAt, List<Line> records, long nextId) {
        public Report { records = List.copyOf(records); }
    }
}
