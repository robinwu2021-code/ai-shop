package ai.neargo.shop.food.service.impl;

import ai.neargo.shop.common.*;
import ai.neargo.shop.food.entity.*;
import ai.neargo.shop.food.mapper.FoodMappers.*;
import java.util.function.BiConsumer;
import ai.neargo.shop.event.AfterCommit;
import ai.neargo.shop.spi.food.FoodStockSyncRequested;
import ai.neargo.shop.spi.food.FoodStockFingerprint;
import ai.neargo.shop.spi.inventory.InventoryFoodStockProjectionPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Set;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/** Joins the FOOD source transaction; the OFF path never touches inventory services. */
public final class FoodStockSyncPublisher {
    private final StockSyncConfigMapper configs;private final StockSyncItemStateMapper states;
    private final StockSyncMappingMapper mappings;private final StockSyncDeliveryMapper deliveries;
    private final StockItemMapper items;private final StockBalanceMapper balances;
    private final ObjectMapper json;
    private BiConsumer<String,String> delivery=(entity,store)->{};
    public void delivery(BiConsumer<String,String> action){delivery=action;}

    public FoodStockSyncPublisher(StockSyncConfigMapper configs,StockSyncItemStateMapper states,
            StockSyncMappingMapper mappings,StockSyncDeliveryMapper deliveries,StockItemMapper items,
            StockBalanceMapper balances,ObjectMapper json) {
        this.configs=configs;this.states=states;this.mappings=mappings;this.deliveries=deliveries;
        this.items=items;this.balances=balances;this.json=json;
    }
    public void capture(String entity,String store,String itemNo) {
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Stock events must join source transaction");
        var c=configs.selectOne(Wrappers.<FnbStockSyncConfig>lambdaQuery().eq(FnbStockSyncConfig::getTenantNo,"MAIN")
                .eq(FnbStockSyncConfig::getEntityNo,entity).eq(FnbStockSyncConfig::getStoreNo,store)
                .eq(FnbStockSyncConfig::getTargetSystem,"INVENTORY").last("FOR UPDATE"));
        if(c==null||!Set.of("BOOTSTRAPPING","ACTIVE","ERROR").contains(c.getStatus()))return;
        var item=items.selectOne(Wrappers.<FnbStockItem>lambdaQuery().eq(FnbStockItem::getTenantNo,"MAIN")
                .eq(FnbStockItem::getEntityNo,entity).eq(FnbStockItem::getItemNo,itemNo));
        var b=balances.lockBalance(entity,store,itemNo);
        if(item==null||b==null||b.getTrackingMode()==null||b.getStatus()==null)return;
        var state=states.selectOne(Wrappers.<FnbStockSyncItemState>lambdaQuery().eq(FnbStockSyncItemState::getTenantNo,"MAIN")
                .eq(FnbStockSyncItemState::getEntityNo,entity).eq(FnbStockSyncItemState::getStoreNo,store)
                .eq(FnbStockSyncItemState::getItemNo,itemNo).last("FOR UPDATE"));
        if(state!=null&&state.getSourceVersion()==Long.MAX_VALUE)throw BizException.of(ErrorCode.CONFLICT);
        long version=state==null?1:state.getSourceVersion()+1;
        var mapping=mappings.selectOne(Wrappers.<FnbStockSyncMapping>lambdaQuery().eq(FnbStockSyncMapping::getTenantNo,"MAIN")
                .eq(FnbStockSyncMapping::getEntityNo,entity).eq(FnbStockSyncMapping::getStoreNo,store)
                .eq(FnbStockSyncMapping::getItemNo,itemNo).eq(FnbStockSyncMapping::getTargetSystem,"INVENTORY"));
        boolean blocked=mapping!=null&&(!"ACTIVE".equals(mapping.getStatus())||!item.getBaseUnit().equals(mapping.getSourceUnit())||!item.getBaseUnit().equals(mapping.getTargetUnit()));
        boolean tracked="TRACKED".equals(b.getTrackingMode());
        var snapshot=new InventoryFoodStockProjectionPort.Snapshot(itemNo,item.getBaseUnit(),b.getTrackingMode(),
                "ACTIVE".equals(item.getStatus())?b.getStatus():"DISABLED",tracked?b.getOnHand():null,
                tracked?b.getReservedQty():null,version,b.getUpdatedAt(),mapping==null?null:mapping.getTargetOwnerRef(),
                mapping==null?null:mapping.getTargetLocationRef(),mapping==null?null:mapping.getTargetItemRef());
        String hash=FoodStockFingerprint.sha256(json.writeValueAsString(new Object[]{new InventoryFoodStockProjectionPort.Scope(entity,store),c.getGeneration(),snapshot}));
        if(state==null){state=new FnbStockSyncItemState();audit(state,entity,store);state.setItemNo(itemNo);state.setSourceVersion(version);state.setSnapshotHash(hash);states.insert(state);}
        else{state.setSourceVersion(version);state.setSnapshotHash(hash);if(states.updateById(state)!=1)throw BizException.of(ErrorCode.CONFLICT);}
        var event=new FoodStockSyncRequested(BizKey.next("FSS"),entity,store,c.getGeneration(),"APPLY",hash,snapshot);
        enqueue(event,itemNo,version,blocked);
    }
    public void control(String entity,String store,long generation,String action,long sequence) {
        var event=new FoodStockSyncRequested(BizKey.next("FSS"),entity,store,generation,action,
                FoodStockFingerprint.sha256(action+":"+generation),null);
        enqueue(event,"__CONTROL__",sequence,false);
    }
    public void reconciliation(String entity,String store,long generation,String number) {
        var event=new FoodStockSyncRequested(number,entity,store,generation,"RECONCILE",FoodStockFingerprint.sha256("RECONCILE:"+number),null);
        enqueue(event,number,0,false);
    }
    public void retry(FnbStockSyncDelivery delivery) {
        if(delivery.getPayloadSnapshot()==null)throw BizException.of(ErrorCode.CONFLICT);
        var event=json.readValue(delivery.getPayloadSnapshot(),FoodStockSyncRequested.class);
        signal(event.entityNo(),event.storeNo());
    }
    private void enqueue(FoodStockSyncRequested event,String item,long version,boolean blocked) {
        var d=new FnbStockSyncDelivery();audit(d,event.entityNo(),event.storeNo());d.setSyncNo(event.syncNo());
        d.setEventNo(event.syncNo());d.setItemNo(item);d.setGeneration(event.generation());d.setSourceVersion(version);
        d.setPayloadHash(event.payloadHash());d.setOperationKey(event.syncNo());d.setPayloadSnapshot(json.writeValueAsString(event));
        d.setStatus(blocked?"BLOCKED_MAPPING":"PENDING");d.setAttempts(0);deliveries.insert(d);
        if(blocked)return;
        signal(event.entityNo(),event.storeNo());
    }
    private void signal(String entity,String store) {
        AfterCommit.run("food-stock-sync "+entity+"/"+store,()->delivery.accept(entity,store));
    }
    private static void audit(BaseEntity row,String entity,String store) {
        row.setTenantNo("MAIN");row.setVersion(0L);row.setDeleted(0);row.setCreatedAt(LocalDateTime.now());row.setUpdatedAt(row.getCreatedAt());row.setCreatedBy("SYSTEM");row.setUpdatedBy("SYSTEM");
        if(row instanceof FnbStockSyncItemState s){s.setEntityNo(entity);s.setStoreNo(store);}
        if(row instanceof FnbStockSyncDelivery d){d.setEntityNo(entity);d.setStoreNo(store);}
    }
}
