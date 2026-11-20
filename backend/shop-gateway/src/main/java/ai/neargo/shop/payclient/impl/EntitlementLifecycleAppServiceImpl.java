package ai.neargo.shop.payclient.impl;

import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.dto.LifecycleViews.ReminderPage;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.pay.entitlement.dto.LifecycleViews.FreezeCommand;
import ai.neargo.shop.pay.entitlement.dto.LifecycleViews.FreezeView;
import ai.neargo.shop.pay.entitlement.service.EntitlementLifecycleService;
import ai.neargo.shop.payclient.EntitlementLifecycleAppService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Direct delegation preserves the owner's transaction and audit boundary. */
@Service
@ConditionalOnProperty(name="shop.pay.deployment",havingValue="embedded",matchIfMissing=true)
public class EntitlementLifecycleAppServiceImpl implements EntitlementLifecycleAppService {
    private final EntitlementLifecycleService pay;
    public EntitlementLifecycleAppServiceImpl(EntitlementLifecycleService pay) { this.pay=pay; }
    @Override public FreezeView freeze(CatalogScope s,String no,FreezeCommand c,String key) {
        return pay.freeze(s,no,c,key);
    }
    @Override public FreezeView state(CatalogScope s,String no) { return pay.state(s,no); }
    @Override public int expire(EntitlementContext ctx,int limit) { return pay.expireDue(ctx,limit); }
    @Override public ReminderPage expiringSoon(EntitlementContext ctx, long afterId, int limit) {
        return pay.expiringSoon(ctx, afterId, limit);
    }
}
