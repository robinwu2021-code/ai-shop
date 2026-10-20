package ai.neargo.shop.pay.client;

import ai.neargo.shop.spi.entitlement.ContractChecks;
import ai.neargo.shop.spi.entitlement.EntitlementContext;

/** Usage attribution is an authenticated internal read, never a client-supplied effect or amount. */
public final class PayMarketingRequests {
    private PayMarketingRequests() { }
    public record Usage(EntitlementContext context,String entityNo,String usageNo,String afterActivityNo,int limit) {
        public Usage {
            ContractChecks.present(context); ContractChecks.id(entityNo); ContractChecks.id(usageNo);
            ContractChecks.require(afterActivityNo!=null && afterActivityNo.length()<=64
                    && afterActivityNo.equals(afterActivityNo.strip()) && limit>=1 && limit<=100);
        }
    }
}
