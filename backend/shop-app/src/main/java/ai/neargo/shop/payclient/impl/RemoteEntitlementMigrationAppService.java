package ai.neargo.shop.payclient.impl;

import ai.neargo.shop.pay.client.PayInternalApi;
import ai.neargo.shop.pay.client.PayMigrationRequests;
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
import ai.neargo.shop.pay.migration.service.LegacyCouponAliasComparator;
import ai.neargo.shop.pay.migration.service.LegacyCouponMigrationNormalizer;
import ai.neargo.shop.payclient.EntitlementMigrationAppService;
import ai.neargo.shop.payclient.RemoteEntitlementCalls;
import ai.neargo.shop.spi.entitlement.LegacyCouponMigrationPlan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name="shop.pay.deployment",havingValue="standalone")
public class RemoteEntitlementMigrationAppService implements EntitlementMigrationAppService {
    private final PayInternalApi pay;
    public RemoteEntitlementMigrationAppService(PayInternalApi pay) { this.pay=pay; }
    @Override public ImportView importPlan(CatalogScope scope,ImportCommand command,String key) {
        return RemoteEntitlementCalls.call(()->pay.migrationImport(new PayMigrationRequests.Import(scope,command,key)));
    }
    @Override public ImportView getImport(CatalogScope scope,String no) {
        return RemoteEntitlementCalls.call(()->pay.migrationImportGet(new PayMigrationRequests.PlanGet(scope,no)));
    }
    @Override public LegacyCouponMigrationPlan preparedPlan(CatalogScope scope,String no) {
        return RemoteEntitlementCalls.call(()->pay.migrationPlanPrepared(new PayMigrationRequests.PlanGet(scope,no)));
    }
    @Override public PlanView plan(CatalogScope scope,PlanCommand command,String key) {
        return RemoteEntitlementCalls.call(()->pay.migrationPlan(new PayMigrationRequests.Plan(scope,command,key)));
    }
    @Override public PlanView getPlan(CatalogScope scope,String no) {
        return RemoteEntitlementCalls.call(()->pay.migrationPlanGet(new PayMigrationRequests.PlanGet(scope,no)));
    }
    @Override public PlanView verifyPlan(CatalogScope scope,String no) {
        return RemoteEntitlementCalls.call(()->pay.migrationPlanVerify(new PayMigrationRequests.PlanGet(scope,no)));
    }
    @Override public MigrationStageViews.ManifestStageView stageManifest(
            CatalogScope scope,StageCommand command,String key) {
        return RemoteEntitlementCalls.call(() -> pay.migrationStageManifest(new PayMigrationRequests.Stage(scope,command,key)));
    }
    @Override public StageView stage(CatalogScope scope,StageCommand command,String key) {
        return RemoteEntitlementCalls.call(() -> pay.migrationStage(new PayMigrationRequests.Stage(scope,command,key)));
    }
    @Override public StageView get(CatalogScope scope,String no) {
        return RemoteEntitlementCalls.call(() -> pay.migrationStageGet(new PayMigrationRequests.Get(scope,no)));
    }
    @Override public Verification verify(CatalogScope scope,String no) {
        return RemoteEntitlementCalls.call(() -> pay.migrationStageVerify(new PayMigrationRequests.Get(scope,no)));
    }
    @Override public LegacyCouponMigrationNormalizer.Report assess(
            CatalogScope scope,String no) {
        return RemoteEntitlementCalls.call(() -> pay.migrationStageAssess(new PayMigrationRequests.Get(scope,no)));
    }
    @Override public LegacyCouponAliasComparator.Comparison compareAlias(CatalogScope scope,MigrationAliasViews.Pair pair) {
        return RemoteEntitlementCalls.call(() -> pay.migrationAliasCompare(new PayMigrationRequests.AliasPair(scope,pair)));
    }
    @Override public MigrationAliasViews.AliasView recognizeAlias(CatalogScope scope,MigrationAliasViews.Recognize command,String key) {
        return RemoteEntitlementCalls.call(() -> pay.migrationAliasRecognize(new PayMigrationRequests.AliasRecognition(scope,command,key)));
    }
    @Override public MigrationAliasViews.AliasView getAlias(CatalogScope scope,String no) {
        return RemoteEntitlementCalls.call(() -> pay.migrationAliasGet(new PayMigrationRequests.AliasGet(scope,no)));
    }
}
