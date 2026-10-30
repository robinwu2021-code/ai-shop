package ai.neargo.shop.food.foodclient;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.dto.FoodStockContracts.*;
import ai.neargo.shop.food.service.FoodStockSyncService.*;
import ai.neargo.shop.food.service.FoodStockReconciliationService.*;
import java.time.LocalDateTime;
import java.util.List;
import ai.neargo.shop.food.service.*;
import ai.neargo.shop.food.spi.FoodStockManagementInternalApi;

public final class RemoteFoodStockSyncService implements FoodStockSyncService {
    private final FoodStockManagementInternalApi api;
    public RemoteFoodStockSyncService(FoodStockManagementInternalApi api) {this.api=api;}
    @Override public ConfigVO config(String entityNo, String storeNo) {return FoodStockRemoteCalls.invoke(()->api.config(new FoodStockManagementInternalApi.StoreQuery(entityNo,storeNo)));}
    @Override public Preflight preflight(String entityNo, String storeNo) {return FoodStockRemoteCalls.invoke(()->api.preflight(new FoodStockManagementInternalApi.StoreQuery(entityNo,storeNo)));}
    @Override public ConfigVO configure(String entityNo, String storeNo, String actorNo, ConfigCommand input, String key) {return FoodStockRemoteCalls.invoke(()->api.configure(new FoodStockManagementInternalApi.ConfigWrite(entityNo,storeNo,actorNo,input,key)));}
    @Override public PageData<MappingVO> mappings(String entityNo, String storeNo, int page, int size) {return FoodStockRemoteCalls.invoke(()->api.mappings(new FoodStockManagementInternalApi.PageQuery(entityNo,storeNo,page,size)));}
    @Override public MappingVO map(String entityNo, String storeNo, String itemNo, String actorNo, MappingCommand input, String key) {return FoodStockRemoteCalls.invoke(()->api.map(new FoodStockManagementInternalApi.MappingWrite(entityNo,storeNo,itemNo,actorNo,input,key)));}
    @Override public PageData<DeliveryVO> deliveries(String entityNo, String storeNo, int page, int size, String status, String itemNo) {return FoodStockRemoteCalls.invoke(()->api.deliveries(new FoodStockManagementInternalApi.DeliveryQuery(entityNo,storeNo,page,size,status,itemNo)));}
    @Override public DeliveryVO retry(String entityNo, String storeNo, String syncNo, String actorNo, String key) {return FoodStockRemoteCalls.invoke(()->api.retry(new FoodStockManagementInternalApi.RetryCommand(entityNo,storeNo,syncNo,actorNo,key)));}
}
