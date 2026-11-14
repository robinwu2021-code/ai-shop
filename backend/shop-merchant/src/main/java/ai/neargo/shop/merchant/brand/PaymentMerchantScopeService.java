package ai.neargo.shop.merchant.brand;

import ai.neargo.shop.merchant.brand.ManagementModels.Actor;
import java.util.List;
import java.util.Map;

/** Payment merchant choices filtered by current entity, resource and method authority. */
public interface PaymentMerchantScopeService {
    /** Return only authorized payment merchants supporting the requested route and method. */
    List<Map<String,Object>> candidates(Actor a,String m,String entity,String channel,String scene,String store);
}
