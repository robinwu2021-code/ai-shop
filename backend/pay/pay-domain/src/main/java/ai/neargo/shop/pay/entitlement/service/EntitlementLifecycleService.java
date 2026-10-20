package ai.neargo.shop.pay.entitlement.service;

import ai.neargo.shop.pay.entitlement.entity.EntInstance;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.dto.LifecycleViews.ReminderPage;
import ai.neargo.shop.pay.entitlement.dto.LifecycleViews.FreezeCommand;
import ai.neargo.shop.pay.entitlement.dto.LifecycleViews.FreezeView;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition;
import java.time.Instant;
import java.time.LocalDate;

/** Mutates a locked instance and its original service-day bucket inside the caller's pay transaction. */
public interface EntitlementLifecycleService {
    /** Marks elapsed windows without deleting remaining units or releasing outstanding source commitments. */
    ReminderPage expiringSoon(EntitlementContext context, long afterId, int limit);
    int expireDue(EntitlementContext context, int limit);
    void reserve(EntitlementContext context, EntInstance asset, EntitlementDefinition rule, LocalDate day, int units);
    void settle(EntitlementContext context, EntInstance asset, EntitlementDefinition rule, LocalDate day,
                int units, boolean commit, Instant firstUseAt);
    /** Freezing gates new reservations, while existing holds still finish against their original source. */
    FreezeView state(CatalogScope scope,String instanceNo);
    FreezeView freeze(CatalogScope scope,String instanceNo,FreezeCommand command,String key);
}
