package ai.neargo.shop.spi.storedvalue;
/** Fresh Core organization facts for new issuance only. Absence must never invalidate an already received receipt. */
public interface StoredValuePolicyFactsPort {
 StoredValuePolicies.StoreScope current(String tenant,String entity,String store,String activityVersion);
}
