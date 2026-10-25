package ai.neargo.shop.merchant.brand;

import ai.neargo.shop.merchant.brand.ManagementModels.Actor;
import ai.neargo.shop.merchant.brand.ManagementModels.Page;
import java.util.Map;

/** Returns only resource rows permitted by the current grant chain. */
public interface ManagementQueryService {
    /** List only resources visible under the current authority and original pagination contract. */
    Page list(Actor a,String m,String type,String brand,String keyword,String status,String cursor,int size);

    /** List effective or explicit resource grants after validating the requested view. */
    Page resources(Actor a,String m,String type,String view,String actorNo,String cursor,int size);

    /** List only active recipients reachable through the current delegation authority. */
    Page recipients(Actor a,String m,String cursor,int size);

    /** Project live store bindings and currently authorized actions. */
    Map<String,Object> operating(Actor a,String m,String store);

    /** Read durable audit facts after validating brand audit access. */
    Page audit(Actor a,String brand,String cursor,int size);
}
