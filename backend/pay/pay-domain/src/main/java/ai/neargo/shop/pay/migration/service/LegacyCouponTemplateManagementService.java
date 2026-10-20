package ai.neargo.shop.pay.migration.service;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.migration.dto.LegacyCouponTemplateViews.*;
public interface LegacyCouponTemplateManagementService {
    Snapshot save(CatalogScope scope,Reference reference,DefinitionChange change,String requestKey);
    Snapshot archive(CatalogScope scope,Reference reference,boolean archived,String requestKey);
    Snapshot get(CatalogScope scope,Reference reference);
    Snapshot status(CatalogScope scope,Reference reference,String status,String requestKey);
}
