package ai.neargo.shop.pay.migration.dto;
import ai.neargo.shop.spi.entitlement.ContractChecks;
import java.time.Instant;
public final class MigrationImportViews {
    private MigrationImportViews() { }
    public record ImportCommand(String planNo,String reason) {
        public ImportCommand { ContractChecks.id(planNo);ContractChecks.require(reason!=null && !reason.isBlank() && reason.length()<=500); }
    }
    public record ImportView(String importNo,String planNo,String entityNo,String sourceDecisionNo,String planHash,
                             String status,long assetCount,long archivalCount,boolean issuanceEnabledAtImport,Instant importedAt) {
        public ImportView {
            ContractChecks.id(importNo);ContractChecks.id(planNo);ContractChecks.id(entityNo);ContractChecks.id(sourceDecisionNo);
            ContractChecks.hash(planHash);ContractChecks.present(importedAt);
            ContractChecks.require("IMPORTED".equals(status) && assetCount>=0 && archivalCount>=0 && !issuanceEnabledAtImport);
        }
    }
}
