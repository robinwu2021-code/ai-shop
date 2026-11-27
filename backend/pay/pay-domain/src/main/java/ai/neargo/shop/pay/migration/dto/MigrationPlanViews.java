package ai.neargo.shop.pay.migration.dto;

import ai.neargo.shop.spi.entitlement.ContractChecks;
import java.time.Instant;

/** Operator metadata only, without recipient identities, ciphertext or authority to import. */
public final class MigrationPlanViews {
    private MigrationPlanViews() { }
    public enum Cohort { UNUSED_MERCHANT_COUPONS, UNUSED_STORE_COUPONS, UNUSED_VERSIONED_COUPONS }

    // Omitting the optional selector preserves the command hashes of previously prepared plans.
    public record PlanCommand(String stageNo, String reason,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            Cohort cohort) {
        public PlanCommand(String stageNo, String reason) {
            this(stageNo, reason, null);
        }
        public PlanCommand {
            ContractChecks.id(stageNo);
            ContractChecks.require(reason!=null && !reason.isBlank() && reason.length()<=500);
        }
    }
    public record PlanView(String planNo,String stageNo,String entityNo,String migrationBatchNo,String status,
                           String planHash,String sourceBundleHash,long assetCount,long archivalCount,
                           int versionCount,Instant assessedAt,boolean importReady,long revision, String documentNo) {
        public PlanView(String planNo, String stageNo, String entityNo, String migrationBatchNo, String status, String planHash, String sourceBundleHash, long assetCount, long archivalCount, int versionCount, Instant assessedAt, boolean importReady, long revision) { this(planNo, stageNo, entityNo, migrationBatchNo, status, planHash, sourceBundleHash, assetCount, archivalCount, versionCount, assessedAt, importReady, revision, null); }
        public PlanView withDocumentNo(String documentNo) { return new PlanView(planNo, stageNo, entityNo, migrationBatchNo, status, planHash, sourceBundleHash, assetCount, archivalCount, versionCount, assessedAt, importReady, revision, documentNo); }

        public PlanView {
            ContractChecks.id(planNo);ContractChecks.id(stageNo);ContractChecks.id(entityNo);ContractChecks.id(migrationBatchNo);
            ContractChecks.hash(planHash);ContractChecks.hash(sourceBundleHash);ContractChecks.present(assessedAt);
            ContractChecks.require("PLANNED".equals(status) && !importReady && assetCount>=0 && archivalCount>=0
                    && versionCount>=1 && revision>=0);
        }
    }
}
