package ai.neargo.shop.food.service.impl;

import ai.neargo.shop.common.*;
import ai.neargo.shop.food.entity.*;
import ai.neargo.shop.food.mapper.FoodMappers.*;
import ai.neargo.shop.event.outbox.*;
import ai.neargo.shop.spi.food.FoodStockSyncRequested;
import ai.neargo.shop.spi.food.FoodOutboxRouting;
import ai.neargo.shop.food.service.FoodStockSyncDispatchService;
import ai.neargo.shop.spi.inventory.InventoryFoodStockProjectionPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Receiver calls stay outside FOOD transactions. Finite baseline pages resume from a durable cursor. */
public final class FoodStockSyncDispatcher implements OutboxHandler,OutboxRouteContribution,FoodStockSyncDispatchService {
    private final StockSyncConfigMapper configs;private final StockSyncDeliveryMapper deliveries;
    private final StockItemMapper items;private final StockBalanceMapper balances;
    private final FoodStockSyncPublisher publisher;private final ObjectProvider<InventoryFoodStockProjectionPort> receivers;
    private final ObjectMapper json;private final TransactionTemplate write;
    private final FoodStockReconciliationServiceImpl reconciliation;
    public FoodStockSyncDispatcher(StockSyncConfigMapper configs,StockSyncDeliveryMapper deliveries,
            StockItemMapper items,StockBalanceMapper balances,FoodStockSyncPublisher publisher,
            ObjectProvider<InventoryFoodStockProjectionPort> receivers,ObjectMapper json,PlatformTransactionManager manager,FoodStockReconciliationServiceImpl reconciliation) {
        this.configs=configs;this.deliveries=deliveries;this.items=items;this.balances=balances;this.publisher=publisher;
        this.receivers=receivers;this.json=json;this.reconciliation=reconciliation;write=new TransactionTemplate(manager);
        write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    @Override public String handlerId(){return FoodOutboxRouting.STOCK_SYNC_HANDLER;}
    @Override public boolean externalWorkflow(){return true;}
    @Override public boolean supports(String type){return "FOOD_STOCK_SYNC_REQUESTED".equals(type);}
    @Override public Set<String> consumedArchiveRoutes(){return Set.of("PLATFORM:FOOD_STOCK_SYNC_REQUESTED");}
    @Override public DeliveryResult dispatch(String entityNo,String storeNo,OutboxEnvelope envelope) {
        var original=json.readValue(envelope.payload(),FoodStockSyncRequested.class);
        if(!original.entityNo().equals(entityNo)||!original.storeNo().equals(storeNo))throw BizException.of(ErrorCode.FORBIDDEN);
        return handle(envelope);
    }
    @Override public DeliveryResult handle(OutboxEnvelope envelope) {
        if(!"PLATFORM".equals(envelope.sourceSystem())||envelope.schemaVersion()!=1||!supports(envelope.eventType()))throw new IllegalArgumentException("Invalid stock sync envelope");
        var event=json.readValue(envelope.payload(),FoodStockSyncRequested.class);
        if(!"FOOD_STOCK_SYNC".equals(envelope.aggregateType())||!event.syncNo().equals(envelope.aggregateId()))throw new IllegalArgumentException("Invalid stock sync aggregate");
        boolean eligible=Boolean.TRUE.equals(write.execute(tx->{var d=delivery(event);if(d==null||!d.getPayloadSnapshot().equals(json.writeValueAsString(event)))throw new IllegalArgumentException("Missing original sync intent");
            if("COMPLETED".equals(d.getStatus())||"CANCELLED".equals(d.getStatus()))return false;
            var c=config(event,false);if(c==null||c.getGeneration()!=event.generation()||("OFF".equals(c.getStatus())||"DISABLING".equals(c.getStatus()))&&!"DEACTIVATE".equals(event.action())){
                d.setStatus("CANCELLED");deliveries.updateById(d);return false;}
            if(d.getAttempts()==Integer.MAX_VALUE)throw new IllegalStateException("Sync attempt counter exhausted");
            d.setAttempts(d.getAttempts()+1);if(deliveries.updateById(d)!=1)throw BizException.of(ErrorCode.CONFLICT);return true;}));
        if(!eligible)return DeliveryResult.sent();var receiver=receivers.getIfUnique();
        if(receiver==null){error(event,"RECEIVER_UNAVAILABLE");return DeliveryResult.retry("RECEIVER_UNAVAILABLE","Inventory projection receiver is unavailable");}
        var scope=new InventoryFoodStockProjectionPort.Scope(event.entityNo(),event.storeNo());
        try {
            if("RECONCILE".equals(event.action())){
                if(!reconciliation.executePage(event))return DeliveryResult.retry("RECONCILIATION_PENDING","Resume from committed reconciliation cursor");
                write.executeWithoutResult(tx->{var d=delivery(event);if(d!=null){d.setStatus("COMPLETED");d.setAckRef(event.syncNo());if(deliveries.updateById(d)!=1)throw BizException.of(ErrorCode.CONFLICT);}});
                return DeliveryResult.sent();
            }
            var result=receiver.queryReceipt(scope,event.syncNo(),event.payloadHash());
            if(result==null)result=switch(event.action()) {
                case "ACTIVATE"->receiver.activate(scope,event.generation(),event.syncNo());
                case "DEACTIVATE"->receiver.deactivate(scope,event.generation(),event.syncNo());
                case "APPLY"->receiver.apply(scope,event.generation(),event.syncNo(),event.payloadHash(),event.snapshot());
                default->throw new IllegalArgumentException("Unsupported sync action");};
            if(result==null||result.generation()!=event.generation()||result.receiptNo()==null||result.receiptNo().isBlank())
                throw new IllegalStateException("Invalid receiver receipt");
            boolean confirmed=switch(event.action()) {
                case "ACTIVATE"->"ACTIVE".equals(result.status());
                case "DEACTIVATE"->"OFF".equals(result.status());
                case "APPLY"->Set.of("APPLIED","UNCHANGED","STALE").contains(result.status()==null?"":result.status())
                        &&result.appliedVersion()!=null&&result.appliedVersion()>=event.snapshot().sourceVersion();
                default->false;
            };
            if(!confirmed)throw new IllegalStateException("Projection intent has no confirmed receipt");
            if("ACTIVATE".equals(event.action())&&!baseline(event))return DeliveryResult.retry("BASELINE_PENDING","Baseline page captured; resume from committed cursor");
            final var receipt=result;
            write.executeWithoutResult(tx->{var c=config(event,true);if(c==null||c.getGeneration()!=event.generation())return;
                if("DEACTIVATE".equals(event.action())&&"DISABLING".equals(c.getStatus())){c.setStatus("OFF");c.setDisabledAt(LocalDateTime.now());if(configs.updateById(c)!=1)throw BizException.of(ErrorCode.CONFLICT);}
                var d=delivery(event);if(d!=null&&!"CANCELLED".equals(d.getStatus())){d.setStatus("COMPLETED");d.setAckRef(receipt.receiptNo());if(deliveries.updateById(d)!=1)throw BizException.of(ErrorCode.CONFLICT);}});
            return DeliveryResult.sent();
        }catch(RuntimeException unavailable){error(event,"RECEIVER_OR_SOURCE_ERROR");return DeliveryResult.retry("RECEIVER_OR_SOURCE_ERROR","Retry the original durable projection intent");}
    }
    private boolean baseline(FoodStockSyncRequested event) {
        var c=write.execute(tx->config(event,false));if(c==null||c.getGeneration()!=event.generation()||!"BOOTSTRAPPING".equals(c.getStatus()))return true;
        var candidates=write.execute(tx->balances.selectList(Wrappers.<FnbStockBalance>lambdaQuery().eq(FnbStockBalance::getTenantNo,"MAIN")
                .eq(FnbStockBalance::getEntityNo,event.entityNo()).eq(FnbStockBalance::getStoreNo,event.storeNo())
                .gt(FnbStockBalance::getId,c.getBaselineCursor()).le(FnbStockBalance::getId,c.getBaselineEndId()).orderByAsc(FnbStockBalance::getId).last("LIMIT 100")));
        for(var b:candidates)write.executeWithoutResult(tx->{
            // Same lock order as source mutations: item, balance, then synchronization config.
            items.selectOne(Wrappers.<FnbStockItem>lambdaQuery().eq(FnbStockItem::getTenantNo,"MAIN").eq(FnbStockItem::getEntityNo,event.entityNo()).eq(FnbStockItem::getItemNo,b.getItemNo()).last("FOR UPDATE"));
            balances.lockBalance(event.entityNo(),event.storeNo(),b.getItemNo());var current=config(event,true);
            if(current==null||current.getGeneration()!=event.generation()||!"BOOTSTRAPPING".equals(current.getStatus())||current.getBaselineCursor()>=b.getId())return;
            publisher.capture(event.entityNo(),event.storeNo(),b.getItemNo());current.setBaselineCursor(b.getId());if(configs.updateById(current)!=1)throw BizException.of(ErrorCode.CONFLICT);
        });
        if(candidates.size()==100)return false;
        write.executeWithoutResult(tx->{var current=config(event,true);if(current!=null&&current.getGeneration()==event.generation()&&"BOOTSTRAPPING".equals(current.getStatus())){current.setStatus("ACTIVE");if(configs.updateById(current)!=1)throw BizException.of(ErrorCode.CONFLICT);}});return true;
    }
    private FnbStockSyncConfig config(FoodStockSyncRequested e,boolean lock){var q=Wrappers.<FnbStockSyncConfig>lambdaQuery().eq(FnbStockSyncConfig::getTenantNo,"MAIN").eq(FnbStockSyncConfig::getEntityNo,e.entityNo()).eq(FnbStockSyncConfig::getStoreNo,e.storeNo()).eq(FnbStockSyncConfig::getTargetSystem,"INVENTORY");if(lock)q.last("FOR UPDATE");return configs.selectOne(q);}
    private FnbStockSyncDelivery delivery(FoodStockSyncRequested e){return deliveries.selectOne(Wrappers.<FnbStockSyncDelivery>lambdaQuery().eq(FnbStockSyncDelivery::getTenantNo,"MAIN").eq(FnbStockSyncDelivery::getEntityNo,e.entityNo()).eq(FnbStockSyncDelivery::getStoreNo,e.storeNo()).eq(FnbStockSyncDelivery::getSyncNo,e.syncNo()).last("FOR UPDATE"));}
    private void error(FoodStockSyncRequested e,String code){write.executeWithoutResult(tx->{var d=delivery(e);if(d!=null&&!Set.of("COMPLETED","CANCELLED").contains(d.getStatus())){d.setStatus("RETRYABLE");d.setLastErrorCode(code);d.setNextAttemptAt(LocalDateTime.now().plusMinutes(1));deliveries.updateById(d);}});}
}
