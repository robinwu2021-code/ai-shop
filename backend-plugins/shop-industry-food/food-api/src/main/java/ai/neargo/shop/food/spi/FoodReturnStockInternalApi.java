package ai.neargo.shop.food.spi;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.service.FoodReturnStockService.*;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;
public interface FoodReturnStockInternalApi {
    String ROOT="/internal/food/return-stock";
    record Command(String entityNo,String storeNo,String returnNo,String actorNo,Apply input,String key) {}
    record Query(String entityNo,String storeNo,String returnNo,int page,int size) {}
    record Retry(String entityNo,String storeNo,String returnNo,String operationNo,String actorNo) {}
    @PostExchange(ROOT+"/apply") DispositionVO apply(@RequestBody Command request);
    @PostExchange(ROOT+"/list") PageData<DispositionVO> list(@RequestBody Query request);
    @PostExchange(ROOT+"/retry") DispositionVO retry(@RequestBody Retry request);
}
