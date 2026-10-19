package ai.neargo.shop.pay.entitlement.service;

import ai.neargo.shop.pay.entitlement.entity.EntInstance;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition;
import java.time.Instant;
import java.time.LocalDate;

/** Mutates a locked instance and its original service-day bucket inside the caller's pay transaction. */
public interface EntitlementLifecycleService {
    /** Marks elapsed windows without deleting remaining units or releasing outstanding source commitments. */
    int expireDue(EntitlementContext context, int limit);
    void reserve(EntitlementContext context, EntInstance asset, EntitlementDefinition rule, LocalDate day, int units);
    void settle(EntitlementContext context, EntInstance asset, EntitlementDefinition rule, LocalDate day,
                int units, boolean commit, Instant firstUseAt);
}
