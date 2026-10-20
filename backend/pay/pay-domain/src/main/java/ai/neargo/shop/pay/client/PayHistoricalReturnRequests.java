package ai.neargo.shop.pay.client;
import ai.neargo.shop.spi.entitlement.*;
public final class PayHistoricalReturnRequests {
    private PayHistoricalReturnRequests() { }
    public record Query(EntitlementContext context,String entityNo,String returnNo) {
        public Query {ContractChecks.present(context);ContractChecks.id(entityNo);ContractChecks.id(returnNo);}
    }
}
