package ai.neargo.shop.payclient;

import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.migration.dto.MigrationAliasViews;
import ai.neargo.shop.pay.migration.dto.MigrationImportViews;
import ai.neargo.shop.pay.migration.dto.MigrationPlanViews;
import ai.neargo.shop.pay.migration.dto.MigrationStageViews;
import ai.neargo.shop.pay.migration.dto.MigrationStageViews.StageCommand;
import ai.neargo.shop.pay.migration.dto.MigrationStageViews.StageView;
import ai.neargo.shop.pay.migration.dto.MigrationStageViews.Verification;
import ai.neargo.shop.pay.migration.service.LegacyCouponAliasComparator;
import ai.neargo.shop.pay.migration.service.LegacyCouponMigrationNormalizer;
import ai.neargo.shop.spi.entitlement.LegacyCouponMigrationPlan;

public interface EntitlementMigrationAppService {
    MigrationImportViews.ImportView importPlan(CatalogScope scope,
            MigrationImportViews.ImportCommand command,String requestKey);
    MigrationImportViews.ImportView getImport(CatalogScope scope,String planNo);
    LegacyCouponMigrationPlan preparedPlan(CatalogScope scope,String planNo);
    MigrationPlanViews.PlanView plan(CatalogScope scope,
            MigrationPlanViews.PlanCommand command,String requestKey);
    MigrationPlanViews.PlanView getPlan(CatalogScope scope,String planNo);
    MigrationPlanViews.PlanView verifyPlan(CatalogScope scope,String planNo);
    MigrationStageViews.ManifestStageView stageManifest(
            CatalogScope scope,StageCommand command,String requestKey);
    StageView stage(CatalogScope scope,StageCommand command,String requestKey);
    StageView get(CatalogScope scope,String stageNo);
    Verification verify(CatalogScope scope,String stageNo);
    LegacyCouponMigrationNormalizer.Report assess(CatalogScope scope,String stageNo);
    LegacyCouponAliasComparator.Comparison compareAlias(CatalogScope scope,MigrationAliasViews.Pair pair);
    MigrationAliasViews.AliasView recognizeAlias(CatalogScope scope,MigrationAliasViews.Recognize command,String requestKey);
    MigrationAliasViews.AliasView getAlias(CatalogScope scope,String aliasNo);
}
