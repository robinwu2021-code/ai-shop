package ai.neargo.shop.pay.entitlement.service;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.dto.CatalogViews.PublishedVersionView;
import ai.neargo.shop.pay.entitlement.dto.IssuePolicyViews.PolicyView;
import ai.neargo.shop.pay.entitlement.dto.IssuePolicyViews.SavePolicy;
import ai.neargo.shop.pay.entitlement.dto.ManagedTemplateView;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition;

/** Owner-scoped management aggregation is shared by embedded and independent payment deployment. */
public interface EntitlementManagementService {
    PageData<ManagedTemplateView> list(CatalogScope scope, String kind, String status, long page, long size);
    ManagedTemplateView get(CatalogScope scope, String templateNo);
    ManagedTemplateView create(CatalogScope scope, EntitlementDefinition definition, String key);
    ManagedTemplateView draft(CatalogScope scope, String no, long revision, EntitlementDefinition definition,
            String key);
    PublishedVersionView publish(CatalogScope scope, String no, long revision, String key);
    ManagedTemplateView status(CatalogScope scope, String no, long revision, String status, String key);
    PolicyView policy(CatalogScope scope, String no, SavePolicy command, String key);
}
