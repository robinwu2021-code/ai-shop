package ai.neargo.shop.pay.migration.dto;

import ai.neargo.shop.spi.entitlement.ContractChecks;
import java.time.Instant;

/** Operator metadata only, without recipient identities, ciphertext or authority to import. */
public final class MigrationPlanViews {
    private MigrationPlanViews() { }
    public record PlanCommand(String stageNo,String reason) {
        public PlanCommand {
            ContractChecks.id(stageNo);
            ContractChecks.require(reason!=null && !reason.isBlank() && reason.length()<=500);
        }
    }
    public record PlanView(String planNo,String stageNo,String entityNo,String migrationBatchNo,String status,
                           String planHash,String sourceBundleHash,long assetCount,long archivalCount,
                           int versionCount,Instant assessedAt,boolean importReady,long revision) {
        public PlanView {
            ContractChecks.id(planNo);ContractChecks.id(stageNo);ContractChecks.id(entityNo);ContractChecks.id(migrationBatchNo);
            ContractChecks.hash(planHash);ContractChecks.hash(sourceBundleHash);ContractChecks.present(assessedAt);
            ContractChecks.require("PLANNED".equals(status) && !importReady && assetCount>=0 && archivalCount>=0
                    && versionCount>=1 && revision>=0);
        }
    }
}
