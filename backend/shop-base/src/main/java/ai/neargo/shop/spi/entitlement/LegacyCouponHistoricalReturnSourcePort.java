package ai.neargo.shop.spi.entitlement;
import java.time.Instant;
import java.util.Set;
/** Durable core authority for returning a consumption captured before migration. Not a cash refund command. */
public interface LegacyCouponHistoricalReturnSourcePort {
    Fact resolve(EntitlementContext context,String returnNo);
    void acknowledge(EntitlementContext context,LegacyCouponHistoricalReturnPort.Receipt receipt);
    record Fact(int protocolVersion,String tenantNo,String returnNo,String sourceUsageNo,String sourceInstanceNo,
                String migrationDecisionNo,String migrationBatchNo,String planHash,String userNo,String entityNo,
                String orderNo,String sourceRefNo,String reason,long discountMinor,String currency,String sourceHash,Instant occurredAt) {
        public Fact {
            ContractChecks.require(protocolVersion==1 && discountMinor>=0 && "CNY".equals(currency));
            for(String id:new String[]{tenantNo,returnNo,sourceUsageNo,sourceInstanceNo,migrationDecisionNo,migrationBatchNo,userNo,entityNo,orderNo,sourceRefNo}) ContractChecks.id(id);
            ContractChecks.hash(planHash);ContractChecks.hash(sourceHash);ContractChecks.present(occurredAt);
            ContractChecks.require(Set.of("CANCELLED","FULL_REFUND").contains(reason));
        }
    }
}
