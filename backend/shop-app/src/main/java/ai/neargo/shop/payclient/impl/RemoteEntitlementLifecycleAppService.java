package ai.neargo.shop.payclient.impl;

import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.dto.LifecycleViews.ReminderPage;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.pay.entitlement.dto.LifecycleViews.FreezeCommand;
import ai.neargo.shop.pay.entitlement.dto.LifecycleViews.FreezeView;
import ai.neargo.shop.pay.client.PayInternalApi;
import ai.neargo.shop.pay.client.PayLifecycleRequests;
import ai.neargo.shop.payclient.EntitlementLifecycleAppService;
import ai.neargo.shop.payclient.RemoteEntitlementCalls;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Financial write transport never changes the caller's original idempotency key. */
@Service
@ConditionalOnProperty(name="shop.pay.deployment",havingValue="standalone")
public class RemoteEntitlementLifecycleAppService implements EntitlementLifecycleAppService {
    private final PayInternalApi pay;
    public RemoteEntitlementLifecycleAppService(PayInternalApi pay) { this.pay=pay; }
    @Override public FreezeView freeze(CatalogScope s,String no,FreezeCommand c,String key) {
        return RemoteEntitlementCalls.call(() -> pay.entitlementFreeze(new PayLifecycleRequests.Freeze(s,no,c,key)));
    }
    @Override public FreezeView state(CatalogScope s,String no) {
        return RemoteEntitlementCalls.call(() -> pay.entitlementLifecycleState(new PayLifecycleRequests.State(s,no)));
    }
    @Override public int expire(EntitlementContext ctx,int limit) {
        return RemoteEntitlementCalls.call(() -> pay.entitlementExpire(new PayLifecycleRequests.Expire(ctx,limit))).changed();
    }
    @Override public ReminderPage expiringSoon(EntitlementContext ctx, long afterId, int limit) {
        return RemoteEntitlementCalls.call(() -> pay.entitlementExpiringSoon(
                new PayLifecycleRequests.Reminders(ctx, afterId, limit)));
    }
}
