package ai.neargo.shop.trade.service;

import ai.neargo.shop.spi.entitlement.*;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.SourceIdentity;
import java.util.List;
import java.util.Optional;
import ai.neargo.shop.trade.dto.OrderVO;
import ai.neargo.shop.spi.entitlement.LegacyCouponCheckoutSourcePort.CheckoutFact;
import ai.neargo.shop.spi.entitlement.LegacyCouponCheckoutSourcePort.Decision;
import ai.neargo.shop.spi.entitlement.LegacyCouponCheckoutSourcePort.Reversal;
import ai.neargo.shop.spi.entitlement.LegacyCouponCheckoutSourcePort.IntentKind;
import ai.neargo.shop.spi.entitlement.LegacyCouponCheckoutSourcePort.MerchantGroup;

/** Internal core journal. Stage inputs must come from core order pricing, never request-supplied money. */
public interface LegacyCouponCheckoutJournal {
    CheckoutFact resolve(EntitlementContext ctx,String checkoutNo);
    Optional<Decision> decision(EntitlementContext ctx,String checkoutNo);
    Optional<Reversal> reversal(EntitlementContext ctx,String checkoutNo);
    record Stage(IntentKind intentKind,String checkoutNo,String operationNo,String userNo,String entityNo,
                 SourceIdentity origin,String targetInstanceNo,String ownerBatchNo,String ownerPlanHash,
                 String currency,List<MerchantGroup> groups,String commandHash) {
        public Stage {
            ContractChecks.present(intentKind); ContractChecks.id(checkoutNo); ContractChecks.id(operationNo);
            ContractChecks.id(userNo); ContractChecks.id(entityNo); ContractChecks.present(origin);
            ContractChecks.id(targetInstanceNo); ContractChecks.id(ownerBatchNo); ContractChecks.hash(ownerPlanHash);
            ContractChecks.currency(currency); ContractChecks.hash(commandHash); groups=List.copyOf(groups);
            ContractChecks.require(!groups.isEmpty());
        }
    }
    LegacyCouponBoundOrderService.Binding bindingFor(EntitlementContext ctx,String checkoutNo,LegacyCouponUsePort.Operation held);
    List<OrderVO.DiscountLine> discountLines(String tenantNo,String subOrderNo);
    String originalTitle(String tenantNo,String orderNo,boolean returned);
    boolean decideForRecovery(EntitlementContext ctx,String checkoutNo);
    void acknowledge(EntitlementContext ctx,String checkoutNo,LegacyCouponUsePort.Operation completed);
    CheckoutFact stage(EntitlementContext ctx,Stage command);
    CheckoutFact bind(EntitlementContext ctx,String checkoutNo,String userNo,String commandHash,List<MerchantGroup> actualGroups,
              LegacyCouponUsePort.Operation held);
    Decision commit(EntitlementContext ctx,String checkoutNo,String orderNo,LegacyCouponUsePort.Operation held);
    /** Returns true for a journal-owned order; writes a reversal only for its actual full cancellation. */
    boolean returnCancelledOrder(EntitlementContext ctx,String orderNo);
    /** Fresh committed refund facts; no financial RPC is performed while core rows are locked. */
    Optional<Reversal> returnRefundedOrder(EntitlementContext ctx,String afterSaleNo);
    Decision abort(EntitlementContext ctx,String checkoutNo);
}
