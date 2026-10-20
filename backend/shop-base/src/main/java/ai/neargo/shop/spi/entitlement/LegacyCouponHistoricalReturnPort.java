package ai.neargo.shop.spi.entitlement;
import java.time.Instant;
/** Pay completes only the original core return decision, even if new-use switches are closed. */
public interface LegacyCouponHistoricalReturnPort {
    Receipt reverse(EntitlementContext context,String entityNo,String returnNo);
    Receipt query(EntitlementContext context,String entityNo,String returnNo);
    record Receipt(String receiptNo,String returnNo,String sourceUsageNo,String usageNo,String instanceNo,String userNo,
                   String entityNo,String sourceHash,int restoredUnits,int remainingUnits,Instant occurredAt) {
        public Receipt {
            for(String id:new String[]{receiptNo,returnNo,sourceUsageNo,usageNo,instanceNo,userNo,entityNo}) ContractChecks.id(id);
            ContractChecks.hash(sourceHash);ContractChecks.require(restoredUnits==1 && remainingUnits>=0);ContractChecks.present(occurredAt);
        }
    }
}
