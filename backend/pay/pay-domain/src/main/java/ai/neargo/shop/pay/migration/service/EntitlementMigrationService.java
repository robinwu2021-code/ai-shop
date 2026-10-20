package ai.neargo.shop.pay.migration.service;

import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.migration.dto.MigrationStageViews.StageCommand;
import ai.neargo.shop.pay.migration.dto.MigrationStageViews.StageView;
import ai.neargo.shop.pay.migration.dto.MigrationStageViews.Verification;

/** Evidence staging only. Import and durable source/target ownership decisions require separate protocols. */
public interface EntitlementMigrationService {
    ai.neargo.shop.pay.migration.dto.MigrationStageViews.ManifestStageView stageManifest(
            CatalogScope scope,StageCommand command,String requestKey);
    StageView stage(CatalogScope operator,StageCommand command,String requestKey);
    StageView get(CatalogScope operator,String stageNo);
    Verification verify(CatalogScope operator,String stageNo);
    LegacyCouponMigrationNormalizer.Report assess(CatalogScope scope,String stageNo);
    ai.neargo.shop.pay.migration.service.LegacyCouponAliasComparator.Comparison compareAlias(CatalogScope scope,ai.neargo.shop.pay.migration.dto.MigrationAliasViews.Pair pair);
    ai.neargo.shop.pay.migration.dto.MigrationAliasViews.AliasView recognizeAlias(CatalogScope scope,ai.neargo.shop.pay.migration.dto.MigrationAliasViews.Recognize command,String requestKey);
    ai.neargo.shop.pay.migration.dto.MigrationAliasViews.AliasView getAlias(CatalogScope scope,String aliasNo);
}
