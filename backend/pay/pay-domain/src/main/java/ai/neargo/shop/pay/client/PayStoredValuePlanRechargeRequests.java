package ai.neargo.shop.pay.client;

import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.storedvalue.StoredValuePlanRecharges;

public final class PayStoredValuePlanRechargeRequests {
    private PayStoredValuePlanRechargeRequests() { }
    public record Bind(EntitlementContext context,String customerNo,String userNo) { }
    public record Create(EntitlementContext context, String entityNo, StoredValuePlanRecharges.Create command) { }
    public record Replay(EntitlementContext context, String entityNo, StoredValuePlanRecharges.Replay command) { }
    public record Query(EntitlementContext context, String entityNo, String rechargeNo) { }
}
