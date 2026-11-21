package ai.neargo.shop.payclient.impl;

import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.migration.dto.MigrationAliasViews;
import ai.neargo.shop.pay.migration.dto.MigrationImportViews.ImportCommand;
import ai.neargo.shop.pay.migration.dto.MigrationImportViews.ImportView;
import ai.neargo.shop.pay.migration.dto.MigrationPlanViews.PlanCommand;
import ai.neargo.shop.pay.migration.dto.MigrationPlanViews.PlanView;
import ai.neargo.shop.pay.migration.dto.MigrationStageViews;
import ai.neargo.shop.pay.migration.dto.MigrationStageViews.StageCommand;
import ai.neargo.shop.pay.migration.dto.MigrationStageViews.StageView;
import ai.neargo.shop.pay.migration.dto.MigrationStageViews.Verification;
import ai.neargo.shop.pay.migration.service.EntitlementMigrationPlanService;
import ai.neargo.shop.pay.migration.service.EntitlementMigrationService;
import ai.neargo.shop.pay.migration.service.LegacyCouponAliasComparator;
import ai.neargo.shop.pay.migration.service.LegacyCouponMigrationImportService;
import ai.neargo.shop.pay.migration.service.LegacyCouponMigrationNormalizer;
import ai.neargo.shop.payclient.EntitlementMigrationAppService;
import ai.neargo.shop.spi.entitlement.LegacyCouponMigrationPlan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name="shop.pay.deployment",havingValue="embedded",matchIfMissing=true)
public class EntitlementMigrationAppServiceImpl implements EntitlementMigrationAppService {
    private final EntitlementMigrationService owner;
    private final EntitlementMigrationPlanService plans;
    private final LegacyCouponMigrationImportService imports;
    public EntitlementMigrationAppServiceImpl(EntitlementMigrationService owner,EntitlementMigrationPlanService plans,LegacyCouponMigrationImportService imports) {
        this.owner=owner;this.plans=plans;this.imports=imports;
    }
    @Override public ImportView importPlan(CatalogScope scope,ImportCommand command,String key) {
        return imports.importPlan(scope,command.planNo(),command.reason(),key);
    }
    @Override public ImportView getImport(CatalogScope scope,String no) { return imports.get(scope,no); }
    @Override public LegacyCouponMigrationPlan preparedPlan(CatalogScope scope,String no) {
        return plans.prepared(scope,no);
    }
    @Override public PlanView plan(CatalogScope scope,PlanCommand command,String key) {
        return plans.prepare(scope,command.stageNo(),command.reason(),key);
    }
    @Override public PlanView getPlan(CatalogScope scope,String no) { return plans.get(scope,no); }
    @Override public PlanView verifyPlan(CatalogScope scope,String no) { return plans.verify(scope,no); }
    @Override public MigrationStageViews.ManifestStageView stageManifest(
            CatalogScope scope,StageCommand command,String key) { return owner.stageManifest(scope,command,key); }
    @Override public StageView stage(CatalogScope scope,StageCommand command,String key) { return owner.stage(scope,command,key); }
    @Override public StageView get(CatalogScope scope,String no) { return owner.get(scope,no); }
    @Override public Verification verify(CatalogScope scope,String no) { return owner.verify(scope,no); }
    @Override public LegacyCouponMigrationNormalizer.Report assess(
            CatalogScope scope,String no) { return owner.assess(scope,no); }
    @Override public LegacyCouponAliasComparator.Comparison compareAlias(CatalogScope scope,MigrationAliasViews.Pair pair) {
        return owner.compareAlias(scope,pair);
    }
    @Override public MigrationAliasViews.AliasView recognizeAlias(CatalogScope scope,MigrationAliasViews.Recognize command,String key) {
        return owner.recognizeAlias(scope,command,key);
    }
    @Override public MigrationAliasViews.AliasView getAlias(CatalogScope scope,String no) { return owner.getAlias(scope,no); }
}
