package ai.neargo.shop.spi.entitlement;

import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.SourceIdentity;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.SourceKind;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.SourceSystem;
import java.time.Instant;
import java.util.Optional;

/** Core durable decisions only. No decision producer is enabled by declaring this read contract. */
public interface LegacyCouponSourceCommitPort {
    Optional<CommitProof> decided(EntitlementContext context,SourceIdentity template);

    /** planHash binds the full source bundle and target manifest, not merely the visible V1 watermark. */
    record CommitProof(SourceIdentity template,String migrationBatchNo,String decisionNo,String planHash,Instant decidedAt) {
        public CommitProof {
            ContractChecks.present(template); ContractChecks.id(migrationBatchNo); ContractChecks.id(decisionNo);
            ContractChecks.hash(planHash); ContractChecks.present(decidedAt);
            ContractChecks.require(template.sourceKind()==SourceKind.TEMPLATE && template.sourceSystem()!=SourceSystem.PAY);
        }
    }
}
