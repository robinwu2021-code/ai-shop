package ai.neargo.shop.food.server.api;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.config.ConditionalOnFoodEnabled;
import ai.neargo.shop.food.config.FoodPersistenceConfiguration.PersistenceEnabled;
import ai.neargo.shop.food.service.FoodReturnStockService;
import ai.neargo.shop.food.service.FoodReturnStockService.*;
import ai.neargo.shop.food.spi.FoodReturnStockInternalApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Conditional;
import org.springframework.web.bind.annotation.*;
@RestController @ConditionalOnFoodEnabled @Conditional(PersistenceEnabled.class)
@ConditionalOnProperty(name="shop.food.local-stock.enabled",havingValue="true")
public class InternalFoodReturnStockController extends FoodStockInternalErrors {
    private final FoodReturnStockService service;
    public InternalFoodReturnStockController(FoodReturnStockService service){this.service=service;}
    @PostMapping(FoodReturnStockInternalApi.ROOT+"/apply") public DispositionVO apply(@RequestBody FoodReturnStockInternalApi.Command r){return service.apply(r.entityNo(),r.storeNo(),r.returnNo(),r.actorNo(),r.input(),r.key());}
    @PostMapping(FoodReturnStockInternalApi.ROOT+"/list") public PageData<DispositionVO> list(@RequestBody FoodReturnStockInternalApi.Query r){return service.list(r.entityNo(),r.storeNo(),r.returnNo(),r.page(),r.size());}
    @PostMapping(FoodReturnStockInternalApi.ROOT+"/retry") public DispositionVO retry(@RequestBody FoodReturnStockInternalApi.Retry r){return service.retry(r.entityNo(),r.storeNo(),r.returnNo(),r.operationNo(),r.actorNo());}
}
