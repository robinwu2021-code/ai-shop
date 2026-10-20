package ai.neargo.shop.spi.storedvalue;

import ai.neargo.shop.spi.entitlement.EntitlementContext;

/** Core resolves the verified person binding. This port never accepts a public claimed customer id. */
public interface StoredValueCustomerBindingPort {
    void ensureByUser(EntitlementContext context,String userNo);
}
