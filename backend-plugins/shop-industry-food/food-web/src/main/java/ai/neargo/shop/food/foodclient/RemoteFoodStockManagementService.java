package ai.neargo.shop.food.foodclient;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.dto.FoodStockContracts.*;
import ai.neargo.shop.food.service.FoodStockSyncService.*;
import ai.neargo.shop.food.service.FoodStockReconciliationService.*;
import java.time.LocalDateTime;
import java.util.List;
import ai.neargo.shop.food.service.*;
import ai.neargo.shop.food.spi.FoodStockManagementInternalApi;

public final class RemoteFoodStockManagementService implements FoodStockItemService, FoodStockBalanceService {
    private final FoodStockManagementInternalApi api;
    public RemoteFoodStockManagementService(FoodStockManagementInternalApi api) {this.api=api;}
    @Override public ItemVO createItem(String entityNo, String storeNo, String actorNo, CreateItem input, String key) {return FoodStockRemoteCalls.invoke(()->api.createItem(new FoodStockManagementInternalApi.ItemCreate(entityNo,storeNo,actorNo,input,key)));}
    @Override public ItemVO updateItem(String entityNo, String storeNo, String itemNo, String actorNo, UpdateItem input, String key) {return FoodStockRemoteCalls.invoke(()->api.updateItem(new FoodStockManagementInternalApi.ItemUpdate(entityNo,storeNo,itemNo,actorNo,input,key)));}
    @Override public ItemVO getItem(String entityNo, String storeNo, String itemNo) {return FoodStockRemoteCalls.invoke(()->api.getItem(new FoodStockManagementInternalApi.ItemQuery(entityNo,storeNo,itemNo)));}
    @Override public PageData<ItemVO> listItems(String entityNo, String storeNo, int page, int size, String keyword, String kind, String status) {return FoodStockRemoteCalls.invoke(()->api.listItems(new FoodStockManagementInternalApi.ItemsQuery(entityNo,storeNo,page,size,keyword,kind,status)));}
    @Override public BalanceVO initialize(String entityNo, String storeNo, String actorNo, Initialize input, String key) {return FoodStockRemoteCalls.invoke(()->api.initialize(new FoodStockManagementInternalApi.InitializeCommand(entityNo,storeNo,actorNo,input,key)));}
    @Override public BalanceVO changePolicy(String entityNo, String storeNo, String itemNo, String actorNo, Policy input, String key) {return FoodStockRemoteCalls.invoke(()->api.changePolicy(new FoodStockManagementInternalApi.PolicyCommand(entityNo,storeNo,itemNo,actorNo,input,key)));}
    @Override public BalanceVO adjust(String entityNo, String storeNo, String itemNo, String actorNo, Adjust input, String key) {return FoodStockRemoteCalls.invoke(()->api.adjust(new FoodStockManagementInternalApi.AdjustCommand(entityNo,storeNo,itemNo,actorNo,input,key)));}
    @Override public BalanceVO getBalance(String entityNo, String storeNo, String itemNo) {return FoodStockRemoteCalls.invoke(()->api.getBalance(new FoodStockManagementInternalApi.ItemQuery(entityNo,storeNo,itemNo)));}
    @Override public PageData<BalanceVO> listBalances(String entityNo, String storeNo, int page, int size) {return FoodStockRemoteCalls.invoke(()->api.listBalances(new FoodStockManagementInternalApi.PageQuery(entityNo,storeNo,page,size)));}
    @Override public PageData<MovementVO> listMovements(String entityNo, String storeNo, int page, int size, String itemNo, String action, LocalDateTime from, LocalDateTime to) {return FoodStockRemoteCalls.invoke(()->api.listMovements(new FoodStockManagementInternalApi.MovementsQuery(entityNo,storeNo,page,size,itemNo,action,from,to)));}
}
