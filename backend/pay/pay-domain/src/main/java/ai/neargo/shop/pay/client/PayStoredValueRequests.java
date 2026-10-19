package ai.neargo.shop.pay.client;

import ai.neargo.shop.spi.entitlement.ContractChecks;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.CancelRecharge;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.CreateRecharge;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.CreditRecharge;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.ReceiveRecharge;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.SubmitRecharge;

public final class PayStoredValueRequests {
    private PayStoredValueRequests() { }
    public record Query(EntitlementContext context, String entityNo, String rechargeNo) {
        public Query { check(context, entityNo); ContractChecks.id(rechargeNo); }
    }
    public record Create(EntitlementContext context, String entityNo, CreateRecharge command) {
        public Create { check(context, entityNo); ContractChecks.present(command); }
    }
    public record Submit(EntitlementContext context, String entityNo, SubmitRecharge command) {
        public Submit { check(context, entityNo); ContractChecks.present(command); }
    }
    public record Receive(EntitlementContext context, String entityNo, ReceiveRecharge command) {
        public Receive { check(context, entityNo); ContractChecks.present(command); }
    }
    public record Cancel(EntitlementContext context, String entityNo, CancelRecharge command) {
        public Cancel { check(context, entityNo); ContractChecks.present(command); }
    }
    public record Credit(EntitlementContext context, CreditRecharge command) {
        public Credit { ContractChecks.present(context); ContractChecks.present(command); }
    }
    private static void check(EntitlementContext ctx, String entity) {
        ContractChecks.present(ctx); ContractChecks.id(entity);
    }
}
