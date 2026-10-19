package ai.neargo.shop.pay.entitlement.service;

import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.dto.IssuePolicyViews.PolicyView;
import ai.neargo.shop.pay.entitlement.dto.IssuePolicyViews.SavePolicy;

/** Capacity helpers must join a durable grant transaction; they are not standalone public commands. */
public interface EntitlementIssuePolicyService {
    PolicyView save(CatalogScope scope, String templateNo, SavePolicy command, String requestKey);
    PolicyView get(CatalogScope scope, String templateNo);
    void reserve(CatalogScope scope, String templateNo, String userNo, int quantity, long exposureMinor);
    void settle(CatalogScope scope, String templateNo, String userNo, int quantity, long exposureMinor, boolean issue);
}
