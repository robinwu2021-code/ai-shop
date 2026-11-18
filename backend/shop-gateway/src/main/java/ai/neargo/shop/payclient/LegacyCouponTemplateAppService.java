package ai.neargo.shop.payclient;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.migration.dto.LegacyCouponTemplateViews.*;
/** Application routing contract is distinct from the pay owner service type. */
public interface LegacyCouponTemplateAppService {
    Snapshot archive(CatalogScope scope,Reference reference,boolean archived,String requestKey);
    Snapshot get(CatalogScope scope,Reference reference);
    Snapshot status(CatalogScope scope,Reference reference,String status,String requestKey);
    Snapshot save(CatalogScope scope,Reference reference,DefinitionChange change,String requestKey);
}
