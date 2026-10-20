package ai.neargo.shop.pay.migration.service;

import ai.neargo.shop.pay.entitlement.dto.CatalogScope;

import ai.neargo.shop.pay.entitlement.service.EntitlementRecords;

import ai.neargo.shop.pay.migration.dto.MigrationPlanViews.PlanView;

import ai.neargo.shop.pay.migration.entity.EntMigrationPlan;

import ai.neargo.shop.spi.entitlement.LegacyCouponMigrationPlan;

/** Domain contract; transactional behavior remains in the implementation. */
public interface EntitlementMigrationPlanService {
    /** Internal fixed-plan ownership read for the import coordinator; not an HTTP entity response. */
    EntMigrationPlan owned(CatalogScope scope,String no);
    /** Decrypt only the owned fixed plan supplied by the trusted import coordinator. */
    ai.neargo.shop.pay.migration.service.LegacyCouponTargetPlan decode(EntMigrationPlan row);
    PlanView prepare(CatalogScope scope,String stageNo,String reason,String key);
    PlanView get(CatalogScope scope,String no);
    PlanView verify(CatalogScope scope,String no);
    LegacyCouponMigrationPlan prepared(CatalogScope scope,String no);
public static PlanView view(EntMigrationPlan row) {
        return new PlanView(row.getPlanNo(),row.getStageNo(),row.getEntityNo(),row.getMigrationBatchNo(),row.getStatus(),
                row.getPlanHash(),row.getSourceBundleHash(),row.getAssetCount(),row.getArchivalCount(),row.getVersionCount(),
                EntitlementRecords.instant(row.getAssessedAt()),false,row.getVersion());
    }
}
