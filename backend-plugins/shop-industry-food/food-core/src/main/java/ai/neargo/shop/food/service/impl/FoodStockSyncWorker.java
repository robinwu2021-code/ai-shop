package ai.neargo.shop.food.service.impl;

import ai.neargo.shop.event.outbox.DeliveryResult;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.entity.FnbStockSyncDelivery;
import ai.neargo.shop.food.mapper.FoodMappers.StockSyncDeliveryMapper;
import ai.neargo.shop.food.service.FoodMaintenanceService.Report;
import ai.neargo.shop.spi.food.FoodStockSyncRequested;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.LocalDateTime;
import java.util.Set;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** One existing business journal, short claims and transaction-free HTTP. No extra queue or scheduler. */
public final class FoodStockSyncWorker {
    private static final Set<String> TERMINAL=Set.of("COMPLETED","CANCELLED");
    private final StockSyncDeliveryMapper deliveries;
    private final FoodStockSyncDispatcher dispatcher;
    private final ObjectMapper json;
    private final TransactionTemplate write,outside;
    public FoodStockSyncWorker(StockSyncDeliveryMapper deliveries,FoodStockSyncDispatcher dispatcher,
            ObjectMapper json,PlatformTransactionManager manager){
        this.deliveries=deliveries;this.dispatcher=dispatcher;this.json=json;
        write=new TransactionTemplate(manager);write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        outside=new TransactionTemplate(manager);outside.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
    }
    public void deliver(String entity,String store){outside.executeWithoutResult(tx->drain(entity,store,100));}
    public Report recover(int limit){return outside.execute(tx->drain(null,null,limit));}
    private Report drain(String entity,String store,int limit){
        int processed=0,pending=0;
        for(int i=0;i<limit;i++){
            var row=write.execute(tx->{
                var candidate=deliveries.nextDelivery(entity,store,LocalDateTime.now());
                if(candidate==null)return null;
                candidate.setStatus("PROCESSING");candidate.setNextAttemptAt(LocalDateTime.now().plusSeconds(90));
                if(deliveries.updateById(candidate)!=1)throw BizException.of(ErrorCode.CONFLICT);return candidate;
            });
            if(row==null)break;
            DeliveryResult result;
            try {result=dispatcher.dispatch(json.readValue(row.getPayloadSnapshot(),FoodStockSyncRequested.class));}
            catch(RuntimeException unavailable){result=DeliveryResult.retry("STOCK_SYNC_RETRY",unavailable.getClass().getSimpleName());}
            final var outcome=result;
            boolean completed=Boolean.TRUE.equals(write.execute(tx->{
                var current=deliveries.selectOne(Wrappers.<FnbStockSyncDelivery>lambdaQuery()
                        .eq(FnbStockSyncDelivery::getId,row.getId()).last("FOR UPDATE"));
                if(TERMINAL.contains(current.getStatus()))return true;
                current.setStatus("RETRYABLE");current.setNextAttemptAt(LocalDateTime.now().plusSeconds(2));
                current.setLastErrorCode(outcome.errorCode()==null?"SOURCE_ACK_PENDING":outcome.errorCode());
                if(deliveries.updateById(current)!=1)throw BizException.of(ErrorCode.CONFLICT);return false;
            }));
            if(completed)processed++;else pending++;
        }
        return new Report(processed,pending);
    }
}
