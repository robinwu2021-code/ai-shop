package ai.neargo.shop.food.service.impl;

import ai.neargo.shop.common.*;
import ai.neargo.shop.food.entity.*;
import ai.neargo.shop.food.mapper.FoodMappers.*;
import ai.neargo.shop.food.service.FoodStockReconciliationService;
import ai.neargo.shop.spi.food.FoodStockSyncRequested;
import ai.neargo.shop.spi.food.FoodStockFingerprint;
import ai.neargo.shop.spi.inventory.InventoryFoodStockProjectionPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Async comparison records observations and never overwrites either stock owner. */
public final class FoodStockReconciliationServiceImpl implements FoodStockReconciliationService {
    private final StockSyncReconciliationMapper tasks;private final StockSyncDifferenceMapper differences;
    private final StockSyncConfigMapper configs;private final StockSyncItemStateMapper states;
    private final StockBalanceMapper balances;private final StockItemMapper items;
    private final StockCommandMapper commands;private final FoodStockSyncPublisher publisher;
    private final ObjectProvider<InventoryFoodStockProjectionPort> receivers;
    private final ObjectMapper json;private final TransactionTemplate write;private final TransactionTemplate read;
    public FoodStockReconciliationServiceImpl(StockSyncReconciliationMapper tasks,StockSyncDifferenceMapper differences,
            StockSyncConfigMapper configs,StockSyncItemStateMapper states,StockBalanceMapper balances,
            StockItemMapper items,StockCommandMapper commands,FoodStockSyncPublisher publisher,
            ObjectProvider<InventoryFoodStockProjectionPort> receivers,ObjectMapper json,PlatformTransactionManager manager){
        this.tasks=tasks;this.differences=differences;this.configs=configs;this.states=states;this.balances=balances;
        this.items=items;this.commands=commands;this.publisher=publisher;this.receivers=receivers;this.json=json;
        write=new TransactionTemplate(manager);write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        read=new TransactionTemplate(manager);read.setReadOnly(true);read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    @Override public ReconciliationVO create(String e,String s,String actor,List<String> requested,String key){scope(e,s);text(actor,32);text(key,128);outside();
        if(requested!=null&&(requested.size()>200||requested.stream().anyMatch(Objects::isNull)))throw bad();
        List<String> filter=requested==null?List.of():requested.stream().distinct().sorted().toList();if(filter.size()>200)throw bad();filter.forEach(v->text(v,32));
        String hash=FoodStockFingerprint.sha256(json.writeValueAsString(new Object[]{actor,filter}));
        try{return write.execute(tx->{var prior=tasks.selectOne(Wrappers.<FnbStockSyncReconciliation>lambdaQuery().eq(FnbStockSyncReconciliation::getTenantNo,"MAIN").eq(FnbStockSyncReconciliation::getEntityNo,e).eq(FnbStockSyncReconciliation::getStoreNo,s).eq(FnbStockSyncReconciliation::getOperationKey,key));
            if(prior!=null){if(!hash.equals(prior.getRequestHash()))throw conflict();return summary(prior,1,20);}
            var c=configs.selectOne(Wrappers.<FnbStockSyncConfig>lambdaQuery().eq(FnbStockSyncConfig::getTenantNo,"MAIN").eq(FnbStockSyncConfig::getEntityNo,e).eq(FnbStockSyncConfig::getStoreNo,s).eq(FnbStockSyncConfig::getTargetSystem,"INVENTORY").last("FOR UPDATE"));if(c==null||!"ACTIVE".equals(c.getStatus()))throw conflict();
            if(!filter.isEmpty()&&balances.selectCount(Wrappers.<FnbStockBalance>lambdaQuery()
                    .eq(FnbStockBalance::getTenantNo,"MAIN").eq(FnbStockBalance::getEntityNo,e)
                    .eq(FnbStockBalance::getStoreNo,s).in(FnbStockBalance::getItemNo,filter))!=filter.size())throw bad();
            var max=balances.selectObjs(new QueryWrapper<FnbStockBalance>().select("COALESCE(MAX(id),0)").eq("tenant_no","MAIN").eq("entity_no",e).eq("store_no",s));
            var t=new FnbStockSyncReconciliation();audit(t,e,s,actor);t.setReconciliationNo(BizKey.next("FRC"));t.setGeneration(c.getGeneration());t.setOperationKey(key);t.setRequestHash(hash);t.setRequestSnapshot(json.writeValueAsString(filter));t.setStatus("PENDING");t.setCursorId(0L);t.setEndId(Long.parseLong(max.getFirst().toString()));t.setCheckedCount(0L);t.setDifferenceCount(0L);tasks.insert(t);publisher.reconciliation(e,s,c.getGeneration(),t.getReconciliationNo());return summary(t,1,20);
        });}catch(DuplicateKeyException duplicate){throw conflict();}}
    @Override public ReconciliationVO get(String e,String s,String number,int page,int size){scope(e,s);text(number,32);if(page<1||size<1||size>100)throw bad();outside();return read.execute(tx->{var t=task(e,s,number,false);if(t==null)throw BizException.of(ErrorCode.NOT_FOUND);return summary(t,page,size);});}
    private record Observation(FnbStockBalance balance,String unit,Long sourceVersion,String effectiveStatus){}
    private record Batch(FnbStockSyncReconciliation task,List<Observation> observations){}
    public boolean executePage(FoodStockSyncRequested event){outside();var batch=read.execute(tx->{var t=task(event.entityNo(),event.storeNo(),event.syncNo(),false);if(t==null)throw conflict();
            var filter=Arrays.asList(json.readValue(t.getRequestSnapshot(),String[].class));
            var q=Wrappers.<FnbStockBalance>lambdaQuery().eq(FnbStockBalance::getTenantNo,"MAIN").eq(FnbStockBalance::getEntityNo,event.entityNo()).eq(FnbStockBalance::getStoreNo,event.storeNo()).gt(FnbStockBalance::getId,t.getCursorId()).le(FnbStockBalance::getId,t.getEndId()).in(!filter.isEmpty(),FnbStockBalance::getItemNo,filter).orderByAsc(FnbStockBalance::getId).last("LIMIT 100");
            var observations=new ArrayList<Observation>();for(var b:balances.selectList(q)){var i=items.selectOne(Wrappers.<FnbStockItem>lambdaQuery().eq(FnbStockItem::getTenantNo,"MAIN").eq(FnbStockItem::getEntityNo,event.entityNo()).eq(FnbStockItem::getItemNo,b.getItemNo()));var state=states.selectOne(Wrappers.<FnbStockSyncItemState>lambdaQuery().eq(FnbStockSyncItemState::getTenantNo,"MAIN").eq(FnbStockSyncItemState::getEntityNo,event.entityNo()).eq(FnbStockSyncItemState::getStoreNo,event.storeNo()).eq(FnbStockSyncItemState::getItemNo,b.getItemNo()));observations.add(new Observation(b,i==null?null:i.getBaseUnit(),state==null?null:state.getSourceVersion(),i==null?"UNCONFIGURED":"ACTIVE".equals(i.getStatus())?b.getStatus():"DISABLED"));}return new Batch(t,observations);});
        if("COMPLETED".equals(batch.task.getStatus())||"CANCELLED".equals(batch.task.getStatus()))return true;
        var receiver=receivers.getIfUnique();if(receiver==null)throw conflict();
        var target=receiver.queryProjection(new InventoryFoodStockProjectionPort.Scope(event.entityNo(),event.storeNo()),batch.observations.stream().map(o->o.balance.getItemNo()).toList());
        if(target==null)throw conflict();var targets=new HashMap<String,InventoryFoodStockProjectionPort.Snapshot>();for(var p:target)if(targets.put(p.itemNo(),p)!=null)throw conflict();
        return Boolean.TRUE.equals(write.execute(tx->{var t=task(event.entityNo(),event.storeNo(),event.syncNo(),true);
            if(t==null||t.getGeneration()!=event.generation())throw conflict();
            if(t.getCursorId().longValue()!=batch.task.getCursorId())return "COMPLETED".equals(t.getStatus());
            var c=configs.selectOne(Wrappers.<FnbStockSyncConfig>lambdaQuery().eq(FnbStockSyncConfig::getTenantNo,"MAIN").eq(FnbStockSyncConfig::getEntityNo,event.entityNo()).eq(FnbStockSyncConfig::getStoreNo,event.storeNo()).eq(FnbStockSyncConfig::getTargetSystem,"INVENTORY"));
            if(c==null||c.getGeneration()!=event.generation()||!"ACTIVE".equals(c.getStatus())){t.setStatus("CANCELLED");tasks.updateById(t);return true;}
            long count=0;for(var o:batch.observations){var p=targets.get(o.balance.getItemNo());String kind=difference(o,p);if(kind!=null){var d=new FnbStockSyncDifference();audit(d,event.entityNo(),event.storeNo(),"SYSTEM");d.setDifferenceNo(BizKey.next("FRD"));d.setReconciliationNo(t.getReconciliationNo());d.setItemNo(o.balance.getItemNo());d.setGeneration(event.generation());d.setSourceVersion(o.sourceVersion);d.setTargetVersion(p==null?null:p.sourceVersion());d.setDifferenceType(kind);d.setSourceSnapshot(json.writeValueAsString(new Object[]{o.balance.getItemNo(),o.unit,o.balance.getTrackingMode(),o.balance.getStatus(),o.balance.getOnHand(),o.balance.getReservedQty(),o.sourceVersion}));d.setTargetSnapshot(p==null?null:json.writeValueAsString(p));d.setResolutionStatus("OBSERVED");differences.insert(d);count++;}}
            t.setDifferenceCount(t.getDifferenceCount()+count);t.setCheckedCount(t.getCheckedCount()+batch.observations.size());if(!batch.observations.isEmpty())t.setCursorId(batch.observations.getLast().balance.getId());if(t.getStartedAt()==null)t.setStartedAt(LocalDateTime.now());boolean done=batch.observations.size()<100;t.setStatus(done?"COMPLETED":"RUNNING");if(done)t.setFinishedAt(LocalDateTime.now());if(tasks.updateById(t)!=1)throw conflict();return done;
        }));
    }
    private String difference(Observation o,InventoryFoodStockProjectionPort.Snapshot p){if(o.sourceVersion==null||o.unit==null||o.balance.getTrackingMode()==null)return "SOURCE_UNCONFIGURED";if(p==null)return "MISSING";if(p.sourceVersion()!=o.sourceVersion)return "VERSION_DIFFERENCE";if(!Objects.equals(o.unit,p.baseUnit())||!Objects.equals(o.balance.getTrackingMode(),p.trackingMode())||!Objects.equals(o.effectiveStatus,p.status()))return "STATE_DIFFERENCE";if("TRACKED".equals(p.trackingMode())&&(p.onHand()==null||p.reservedQty()==null||p.onHand().compareTo(o.balance.getOnHand())!=0||p.reservedQty().compareTo(o.balance.getReservedQty())!=0))return "QUANTITY_DIFFERENCE";return null;}
    private FnbStockSyncReconciliation task(String e,String s,String number,boolean lock){var q=Wrappers.<FnbStockSyncReconciliation>lambdaQuery().eq(FnbStockSyncReconciliation::getTenantNo,"MAIN").eq(FnbStockSyncReconciliation::getEntityNo,e).eq(FnbStockSyncReconciliation::getStoreNo,s).eq(FnbStockSyncReconciliation::getReconciliationNo,number);if(lock)q.last("FOR UPDATE");return tasks.selectOne(q);}
    private ReconciliationVO summary(FnbStockSyncReconciliation t,int page,int size){var p=differences.selectPage(new Page<>(page,size),Wrappers.<FnbStockSyncDifference>lambdaQuery().eq(FnbStockSyncDifference::getTenantNo,"MAIN").eq(FnbStockSyncDifference::getEntityNo,t.getEntityNo()).eq(FnbStockSyncDifference::getStoreNo,t.getStoreNo()).eq(FnbStockSyncDifference::getReconciliationNo,t.getReconciliationNo()).orderByAsc(FnbStockSyncDifference::getId));return new ReconciliationVO(t.getReconciliationNo(),t.getStatus(),t.getGeneration(),t.getCheckedCount(),t.getDifferenceCount(),PageData.of(p.getRecords().stream().map(d->new DifferenceVO(d.getDifferenceNo(),d.getItemNo(),d.getDifferenceType(),d.getSourceVersion(),d.getTargetVersion(),d.getSourceSnapshot(),d.getTargetSnapshot(),d.getResolutionStatus())).toList(),p.getTotal(),page,size),t.getCursorId(),t.getEndId(),t.getCreatedAt(),t.getStartedAt(),t.getFinishedAt(),"PAGE_OBSERVED");}
    private static void audit(BaseEntity r,String e,String s,String actor){r.setTenantNo("MAIN");r.setVersion(0L);r.setDeleted(0);r.setCreatedAt(LocalDateTime.now());r.setUpdatedAt(r.getCreatedAt());r.setCreatedBy(actor);r.setUpdatedBy(actor);if(r instanceof FnbStockSyncReconciliation t){t.setEntityNo(e);t.setStoreNo(s);}if(r instanceof FnbStockSyncDifference d){d.setEntityNo(e);d.setStoreNo(s);}}
    private static void scope(String e,String s){text(e,32);text(s,32);}
    private static void text(String s,int max){if(s==null||s.isBlank()||s.length()>max||!s.equals(s.trim()))throw bad();}
    private static void outside(){if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Reconciliation needs a separate transaction");}
    private static BizException bad(){return BizException.of(ErrorCode.BAD_REQUEST);}
    private static BizException conflict(){return BizException.of(ErrorCode.CONFLICT);}
}
