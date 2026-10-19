package ai.neargo.shop.spi.entitlement;

import ai.neargo.shop.spi.entitlement.EntitlementUsePort.CouponAllocation;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.HoldView;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.UseCommand;
import java.util.List;

/** Internal owner facts for core order posting. Never bind or return this shape in public controllers. */
public interface EntitlementUseFactsPort {
    UseFact query(EntitlementContext context, String entityNo, String operationNo);

    record UseFact(HoldView operation, UseCommand command, EntitlementDefinition rule,
                   List<CouponAllocation> couponAllocations, String orderSnapshotHash) {
        public UseFact {
            ContractChecks.present(operation);
            couponAllocations = List.copyOf(ContractChecks.present(couponAllocations));
            if (operation.status() != EntitlementUsePort.State.NOT_FOUND) {
                ContractChecks.present(command);
                ContractChecks.present(rule);
                ContractChecks.hash(orderSnapshotHash);
            }
        }
    }
}
