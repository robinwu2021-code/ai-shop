package ai.neargo.shop.promotion.migration;

import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.LegacyCouponMigrationPlan;
import ai.neargo.shop.spi.entitlement.LegacyCouponSourceCommitPort.CommitProof;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.SourceIdentity;
import java.time.Instant;
import java.util.Optional;

/** Permanent core source decision, separate from the narrow read SPI and from target import. */
public interface LegacyCouponSourceCommitService {
    record Decision(String decisionNo,String planNo,String entityNo,String migrationBatchNo,String planHash,
                    String sourceBundleHash,Instant decidedAt) { }
    Decision commit(EntitlementContext context,LegacyCouponMigrationPlan plan,String reason,String requestKey);
    Optional<CommitProof> decided(EntitlementContext context,SourceIdentity template);
}
