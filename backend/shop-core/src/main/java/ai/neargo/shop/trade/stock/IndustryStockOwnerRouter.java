package ai.neargo.shop.trade.stock;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.food.FoodLocalStockPort;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Select only from a persisted original owner; adapters are objects, never competing global beans. */
@Component
public final class IndustryStockOwnerRouter {
    private final ObjectProvider<FoodLocalStockPort> food;
    private final ObjectProvider<InventoryDemandPlanPort> legacy;
    private final FoodStockBridgeJournal journal;private final ObjectMapper json;private final boolean foodEnabled;
    public IndustryStockOwnerRouter(ObjectProvider<FoodLocalStockPort> food,ObjectProvider<InventoryDemandPlanPort> legacy,
            FoodStockBridgeJournal journal,ObjectMapper json,@Value("${shop.food.local-stock.enabled:false}")boolean foodEnabled) {
        this.food=food;this.legacy=legacy;this.journal=journal;this.json=json;this.foodEnabled=foodEnabled;
    }
    public String newOwner(String verifiedSource){return IndustryStockOwnerPolicy.newOwner(verifiedSource,foodEnabled);}
    public InventoryDemandPlanPort exact(String owner,InventoryDemandPlanPort.Plan plan,int protocol) {
        String selected=IndustryStockOwnerPolicy.frozenOwner(owner,"INDUSTRY_INVENTORY_V2");
        if(IndustryStockOwnerPolicy.FOOD_LOCAL.equals(selected)){
            var providers=food.orderedStream().limit(2).toList();if(providers.size()!=1)throw unsupported();
            return new FoodLocalDemandPlanAdapter(plan,protocol,providers.getFirst(),journal,json);
        }
        if(plan.branches().stream().flatMap(b->b.leaves().stream()).anyMatch(l->l.sourceSkuNo()!=null))throw unsupported();
        var providers=legacy.orderedStream().limit(2).toList();if(providers.size()!=1)throw unsupported();return providers.getFirst();
    }
    private static BizException unsupported(){return BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED);}
}
