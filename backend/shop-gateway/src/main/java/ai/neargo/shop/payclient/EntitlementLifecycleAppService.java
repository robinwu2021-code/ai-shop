package ai.neargo.shop.payclient;

import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.dto.LifecycleViews.ReminderPage;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.pay.entitlement.dto.LifecycleViews.FreezeCommand;
import ai.neargo.shop.pay.entitlement.dto.LifecycleViews.FreezeView;

/** Operator lifecycle access keeps financial and original hold behavior in pay in either deployment mode. */
public interface EntitlementLifecycleAppService {
    FreezeView freeze(CatalogScope scope,String no,FreezeCommand command,String key);
    FreezeView state(CatalogScope scope,String no);
    int expire(EntitlementContext context,int limit);
    ReminderPage expiringSoon(EntitlementContext context, long afterId, int limit);
}
