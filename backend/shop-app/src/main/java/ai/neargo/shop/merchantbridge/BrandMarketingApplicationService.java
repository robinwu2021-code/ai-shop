package ai.neargo.shop.merchantbridge;

import ai.neargo.shop.merchant.brand.ManagementModels.Actor;
import java.util.List;
import java.util.Map;

/** Account entry for ordinary chain marketing. */
public interface BrandMarketingApplicationService {
    Map<String,Object> context(Actor actor, String management, String brand);
    List<Map<String,Object>> list(Actor actor, String management, String brand, String kind);
    Map<String,Object> execute(Actor actor, String management, String brand, String action,
            Object payload, String expectedRevision, String key);
    Map<String,Object> result(Actor actor, String management, String brand, String key);
}
