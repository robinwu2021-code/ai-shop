package ai.neargo.shop.pay.client;

import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.migration.dto.MigrationStageViews.StageCommand;
import ai.neargo.shop.spi.entitlement.ContractChecks;

public final class PayMigrationRequests {
    private PayMigrationRequests() { }
    public record Import(CatalogScope scope,ai.neargo.shop.pay.migration.dto.MigrationImportViews.ImportCommand command,String requestKey) {
        public Import { ContractChecks.present(scope);ContractChecks.present(command);ContractChecks.id(requestKey); }
    }
    public record Plan(CatalogScope scope,ai.neargo.shop.pay.migration.dto.MigrationPlanViews.PlanCommand command,String requestKey) {
        public Plan { ContractChecks.present(scope);ContractChecks.present(command);ContractChecks.id(requestKey); }
    }
    public record PlanGet(CatalogScope scope,String planNo) {
        public PlanGet { ContractChecks.present(scope);ContractChecks.id(planNo); }
    }
    public record Stage(CatalogScope scope,StageCommand command,String requestKey) {
        public Stage {
            ContractChecks.present(scope); ContractChecks.present(command); ContractChecks.id(requestKey);
        }
    }
    public record Get(CatalogScope scope,String stageNo) {
        public Get { ContractChecks.present(scope); ContractChecks.id(stageNo); }
    }
    public record AliasPair(CatalogScope scope,ai.neargo.shop.pay.migration.dto.MigrationAliasViews.Pair pair) {
        public AliasPair { ContractChecks.present(scope); ContractChecks.present(pair); }
    }
    public record AliasRecognition(CatalogScope scope,ai.neargo.shop.pay.migration.dto.MigrationAliasViews.Recognize command,String requestKey) {
        public AliasRecognition {
            ContractChecks.present(scope); ContractChecks.present(command); ContractChecks.id(requestKey);
        }
    }
    public record AliasGet(CatalogScope scope,String aliasNo) {
        public AliasGet { ContractChecks.present(scope); ContractChecks.id(aliasNo); }
    }
}
