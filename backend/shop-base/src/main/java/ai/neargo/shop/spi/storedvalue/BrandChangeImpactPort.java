package ai.neargo.shop.spi.storedvalue;
/** Read-only owner facts. Unavailable dependencies must never be represented as zero. */
public interface BrandChangeImpactPort {
 Impact inspect(String tenantNo,String entityNo,String storeNo);
 record Impact(boolean complete,boolean brandFinancialEnabled,long outstandingMinor,long heldMinor,long pendingRecharges,long unattributedAccounts) {
  public String blocker(){
   if(!complete)return "IMPACT_UNKNOWN";
   if(brandFinancialEnabled)return "DEPENDENCY_NOT_READY";
   if(outstandingMinor!=0||heldMinor!=0||pendingRecharges!=0||unattributedAccounts!=0)return "STORED_VALUE_UNRESOLVED";
   return null;
  }
 }
}
