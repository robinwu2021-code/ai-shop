package ai.neargo.shop.food.foodclient;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.service.FoodReturnStockService;
import ai.neargo.shop.food.spi.FoodReturnStockInternalApi;
public final class RemoteFoodReturnStockService implements FoodReturnStockService {
    private final FoodReturnStockInternalApi api;
    public RemoteFoodReturnStockService(FoodReturnStockInternalApi api){this.api=api;}
    public DispositionVO apply(String e,String s,String number,String actor,Apply input,String key){return FoodStockRemoteCalls.invoke(()->api.apply(new FoodReturnStockInternalApi.Command(e,s,number,actor,input,key)));}
    public PageData<DispositionVO> list(String e,String s,String number,int page,int size){return FoodStockRemoteCalls.invoke(()->api.list(new FoodReturnStockInternalApi.Query(e,s,number,page,size)));}
    public DispositionVO retry(String e,String s,String number,String operation,String actor){return FoodStockRemoteCalls.invoke(()->api.retry(new FoodReturnStockInternalApi.Retry(e,s,number,operation,actor)));}
    public int recover(int limit){throw new IllegalStateException("Return stock recovery is owned by FOOD maintenance");}
}
