package ai.neargo.shop.promotion.service;

import ai.neargo.shop.promotion.dto.ActivityActor;
import ai.neargo.shop.promotion.dto.BrandCouponModels.Completion;
import ai.neargo.shop.promotion.dto.BrandCouponModels.Intent;
import ai.neargo.shop.promotion.dto.BrandCouponModels.Source;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Core owns brand source facts and resumable intents; payment RPCs belong outside these local transactions. */
public interface BrandCouponService {
    Optional<Intent> find(ActivityActor actor, String action, Map<String, Object> request, String key);
    Intent prepare(ActivityActor actor, String action, Map<String, Object> request, String key);
    Intent checkpoint(ActivityActor actor, String commandNo, Map<String, Object> checkpoint);
    Intent finish(ActivityActor actor, String commandNo, Map<String, Object> response, Completion completion,
                  boolean completed);
    Intent result(ActivityActor actor, String key);
    String commandAction(ActivityActor actor, String key);
    List<Intent> issues(ActivityActor actor);
    Source get(ActivityActor actor, String sourceNo);
    List<Source> list(ActivityActor actor);
    Source sourceFact(String tenantNo, String entityNo, String sourceNo);
    Intent issuedCommand(String tenantNo, String entityNo, String actorNo, String commandNo);
}
