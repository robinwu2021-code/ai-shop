package ai.neargo.shop.pay.client;

import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.dto.LifecycleViews.FreezeCommand;
import ai.neargo.shop.spi.entitlement.ContractChecks;
import ai.neargo.shop.spi.entitlement.EntitlementContext;

/** Trusted operator scope is supplied by the application, never the public body. */
public final class PayLifecycleRequests {
    private PayLifecycleRequests() { }
    public record Freeze(CatalogScope scope,String instanceNo,FreezeCommand command,String requestKey) {
        public Freeze {
            ContractChecks.present(scope); ContractChecks.id(instanceNo);
            ContractChecks.present(command); ContractChecks.id(requestKey);
        }
    }
    public record State(CatalogScope scope,String instanceNo) {
        public State { ContractChecks.present(scope); ContractChecks.id(instanceNo); }
    }
    public record Expire(EntitlementContext context,int limit) {
        public Expire { ContractChecks.present(context); ContractChecks.require(limit>=1 && limit<=100); }
    }
    public record Reminders(EntitlementContext context, long afterId, int limit) {
        public Reminders {
            ContractChecks.present(context); ContractChecks.require(afterId >= 0 && limit >= 1 && limit <= 100);
        }
    }
}
