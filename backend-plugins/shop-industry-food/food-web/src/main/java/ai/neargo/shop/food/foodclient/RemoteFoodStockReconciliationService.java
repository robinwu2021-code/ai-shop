package ai.neargo.shop.food.foodclient;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.dto.FoodStockContracts.*;
import ai.neargo.shop.food.service.FoodStockSyncService.*;
import ai.neargo.shop.food.service.FoodStockReconciliationService.*;
import java.time.LocalDateTime;
import java.util.List;
import ai.neargo.shop.food.service.*;
import ai.neargo.shop.food.spi.FoodStockManagementInternalApi;

public final class RemoteFoodStockReconciliationService implements FoodStockReconciliationService {
    private final FoodStockManagementInternalApi api;
    public RemoteFoodStockReconciliationService(FoodStockManagementInternalApi api) {this.api=api;}
    @Override public ReconciliationVO create(String entityNo, String storeNo, String actorNo, List<String> itemNos, String key) {return FoodStockRemoteCalls.invoke(()->api.create(new FoodStockManagementInternalApi.ReconcileCommand(entityNo,storeNo,actorNo,itemNos,key)));}
    @Override public ReconciliationVO get(String entityNo, String storeNo, String number, int page, int size) {return FoodStockRemoteCalls.invoke(()->api.get(new FoodStockManagementInternalApi.ReconcileQuery(entityNo,storeNo,number,page,size)));}
}
