package ai.neargo.shop.pay.client;

import ai.neargo.shop.spi.entitlement.ContractChecks;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.CancelRecharge;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.CreateRecharge;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.CreditRecharge;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.ReceiveRecharge;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.SubmitRecharge;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.BalanceQuery;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.StoreQuery;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.ConsumptionQuery;
import ai.neargo.shop.spi.entitlement.SourceRef;

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
    public record QueryRecharge(EntitlementContext context, String entityNo, String rechargeNo) {
        public QueryRecharge { check(context, entityNo); ContractChecks.id(rechargeNo); }
    }
    public record Balance(EntitlementContext context, BalanceQuery query) {
        public Balance { ContractChecks.present(context); ContractChecks.present(query); }
    }
    public record Stores(EntitlementContext context, StoreQuery query) {
        public Stores { ContractChecks.present(context); ContractChecks.present(query); }
    }
    public record Consumptions(EntitlementContext context, ConsumptionQuery query) {
        public Consumptions { ContractChecks.present(context); ContractChecks.present(query); }
    }
    public record UseQuote(EntitlementContext context, SourceRef sourceRef) {
        public UseQuote { ContractChecks.present(context); ContractChecks.present(sourceRef); }
    }
    private static void check(EntitlementContext ctx, String entity) {
        ContractChecks.present(ctx); ContractChecks.id(entity);
    }
    public record Capabilities(String tenantNo) {
        public Capabilities { ContractChecks.id(tenantNo); }
    }
}
