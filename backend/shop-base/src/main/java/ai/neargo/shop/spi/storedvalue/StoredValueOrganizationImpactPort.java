package ai.neargo.shop.spi.storedvalue;
/** Read-only organization-change evidence, not an authorization or a money mutation. */
public interface StoredValueOrganizationImpactPort {
 Impact inspect(String tenantNo,String entityNo,String storeNo);
 record Impact(String tenantNo,String entityNo,String storeNo,boolean complete,boolean brandFinancialEnabled,
       long outstandingMinor,long heldMinor,long pendingRecharges,long unattributedAccounts) {}
}
