package ai.neargo.shop.promotion.service.impl;

import ai.neargo.shop.promotion.dto.ActivityActor;
import ai.neargo.shop.promotion.service.ActivityCapabilityEvidence;
import ai.neargo.shop.spi.promotion.ActivityCapability;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Evidence for this concrete fixed-cut order adapter, not for legacy flash/gift/group workflows. */
@Component
public class UnifiedOrderActivityCapabilityEvidence implements ActivityCapabilityEvidence {
    @Override public Set<ActivityCapability> verifiedFor(ActivityActor actor) {
        if (!"MAIN".equals(actor.tenantNo())) return Set.of();
        return Set.of(ActivityCapability.ORDER_PRICING, ActivityCapability.AUDIENCE,
                ActivityCapability.DISCOUNT_CAP, ActivityCapability.COMBO_RULE,
                ActivityCapability.ATOMIC_QUOTA, ActivityCapability.PER_USER_LIMIT);
    }
}
