package ai.neargo.shop.pay.client;
import ai.neargo.shop.spi.entitlement.ContractChecks;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.LegacyCouponUsePort;

public final class PayLegacyCouponRequests {
    private PayLegacyCouponRequests() {}
    public record SnapshotRequest(EntitlementContext context,LegacyCouponUsePort.Owned command) {
        public SnapshotRequest {ContractChecks.present(context);ContractChecks.present(command);}
    }
    public record EstimateRequest(EntitlementContext context,LegacyCouponUsePort.EstimateCommand command) {
        public EstimateRequest {ContractChecks.present(context);ContractChecks.present(command);}
    }
    public record PrepareRequest(EntitlementContext context,LegacyCouponUsePort.Prepare command) {
        public PrepareRequest {ContractChecks.present(context);ContractChecks.present(command);}
    }
    public record CompleteRequest(EntitlementContext context,LegacyCouponUsePort.Complete command) {
        public CompleteRequest {ContractChecks.present(context);ContractChecks.present(command);}
    }
    public record Query(EntitlementContext context,String entityNo,String operationNo) {
        public Query {ContractChecks.present(context);ContractChecks.id(entityNo);ContractChecks.id(operationNo);}
    }
}
