package ai.neargo.shop.promotion.service;

import ai.neargo.shop.promotion.dto.ActivityActor;
import ai.neargo.shop.spi.promotion.ActivityCapability;
import java.util.Set;

/** Owner adapters supply verified runtime evidence; presets or client fields cannot assert readiness. */
public interface ActivityCapabilityEvidence {
    Set<ActivityCapability> verifiedFor(ActivityActor actor);
}
