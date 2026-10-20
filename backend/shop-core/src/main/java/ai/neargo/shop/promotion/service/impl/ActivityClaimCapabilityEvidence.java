package ai.neargo.shop.promotion.service.impl;

import ai.neargo.shop.promotion.dto.ActivityActor;
import ai.neargo.shop.promotion.service.ActivityCapabilityEvidence;
import ai.neargo.shop.spi.promotion.ActivityCapability;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Evidence for the tested NONE-trigger claim bundle; other issuance engines retain their own readiness gate. */
@Component
public class ActivityClaimCapabilityEvidence implements ActivityCapabilityEvidence {
    @Override public Set<ActivityCapability> verifiedFor(ActivityActor actor) {
        if (!"MAIN".equals(actor.tenantNo())) return Set.of();
        return Set.of(ActivityCapability.COUPON_RECEIVE, ActivityCapability.COUPON_BUDGET,
                ActivityCapability.MARKETING_RESERVATION, ActivityCapability.PER_USER_LIMIT);
    }
}
