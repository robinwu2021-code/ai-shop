package ai.neargo.shop.promotion;

import ai.neargo.shop.promotion.dto.ActivityActor;
import java.util.List;
import java.util.Map;

/** The management mapper calls this boundary after resolving current brand and publication authority. */
public interface BrandCouponApplicationService {
    Map<String, Object> execute(ActivityActor actor, String action, Map<String, Object> payload, String originalKey);
    Map<String, Object> getResult(ActivityActor actor, String originalKey);
    String commandAction(ActivityActor actor, String originalKey);
    Map<String, Object> detail(ActivityActor actor, String sourceNo);
    List<Map<String, Object>> list(ActivityActor actor);
    List<Map<String, Object>> issues(ActivityActor actor);
}
