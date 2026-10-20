package ai.neargo.shop.pay.migration.service;

import ai.neargo.shop.pay.entitlement.dto.CatalogScope;

import ai.neargo.shop.pay.migration.dto.MigrationImportViews.ImportView;

import ai.neargo.shop.spi.entitlement.*;

import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.*;

/** Domain contract; transactional behavior remains in the implementation. */
public interface LegacyCouponMigrationImportService {
    ImportView importPlan(CatalogScope scope,String no,String reason,String key);
    ImportView get(CatalogScope scope,String no);
}
