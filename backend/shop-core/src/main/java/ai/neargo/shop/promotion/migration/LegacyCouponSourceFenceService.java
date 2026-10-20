package ai.neargo.shop.promotion.migration;

import ai.neargo.shop.spi.entitlement.ContractChecks;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.SourceIdentity;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.SourceSystem;

/** Core owns the source barrier. No target import or owner flip can be authorized by a bare registry lookup. */
public interface LegacyCouponSourceFenceService {
    ai.neargo.shop.spi.entitlement.LegacyCouponFrozenSnapshotPort.Snapshot exportFrozen(EntitlementContext context,ai.neargo.shop.spi.entitlement.LegacyCouponFrozenSnapshotPort.Query query);
    ai.neargo.shop.spi.entitlement.LegacyCouponFrozenSnapshotPort.Snapshot exportManifest(EntitlementContext context,ai.neargo.shop.spi.entitlement.LegacyCouponFrozenSnapshotPort.Query query);
    enum Action { BEGIN_DRAIN, FREEZE, ABORT }
    record Command(Action action, String batchNo, long expectedRevision, String reason, String requestKey) {
        public Command {
            ContractChecks.present(action); ContractChecks.id(batchNo); ContractChecks.id(requestKey);
            ContractChecks.require(expectedRevision >= 0 && reason != null && !reason.isBlank() && reason.length() <= 500);
        }
    }
    record FenceView(SourceSystem sourceSystem, String templateNo, String entityNo, String batchNo, String state,
                     String watermark, Long frozenRevision, long revision) { }
    record FenceAudit(FenceView result, Action action, String actorNo, String reason) { }
    /** Trusted prepared pay plan; must join the durable core decision transaction. */
    void commitSources(EntitlementContext context,ai.neargo.shop.spi.entitlement.LegacyCouponMigrationPlan plan);
    FenceView transition(EntitlementContext context, SourceIdentity template, Command command);
    FenceView state(EntitlementContext context, SourceIdentity template);
    /** Must join the original business transaction and hold the template root until it commits. */
    void requireWritable(String tenantNo, SourceSystem source, String templateNo, boolean originalRelease);
}
