package ai.neargo.shop.food.service.impl;

import ai.neargo.shop.common.*;
import ai.neargo.shop.food.entity.*;
import ai.neargo.shop.food.mapper.FoodMappers.*;
import ai.neargo.shop.food.service.FoodStockSyncService;
import ai.neargo.shop.spi.inventory.InventoryFoodStockProjectionPort;
import ai.neargo.shop.spi.food.FoodStockFingerprint;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Store opt-in configuration; all external receiver calls happen outside FOOD write transactions. */
public final class FoodStockSyncServiceImpl implements FoodStockSyncService {
    private final StockSyncConfigMapper configs;private final StockSyncMappingMapper mappings;
    private final StockSyncDeliveryMapper deliveries;private final StockItemMapper items;
    private final StockBalanceMapper balances;private final StockCommandMapper commands;
    private final ActionLogMapper logs;private final FoodStockSyncPublisher publisher;
    private final ObjectProvider<InventoryFoodStockProjectionPort> receivers;
    private final ObjectMapper json;private final TransactionTemplate write;private final TransactionTemplate read;
    public FoodStockSyncServiceImpl(StockSyncConfigMapper configs,StockSyncMappingMapper mappings,
            StockSyncDeliveryMapper deliveries,StockItemMapper items,StockBalanceMapper balances,
            StockCommandMapper commands,ActionLogMapper logs,FoodStockSyncPublisher publisher,
            ObjectProvider<InventoryFoodStockProjectionPort> receivers,ObjectMapper json,PlatformTransactionManager manager) {
        this.configs=configs;this.mappings=mappings;this.deliveries=deliveries;this.items=items;this.balances=balances;
        this.commands=commands;this.logs=logs;this.publisher=publisher;this.receivers=receivers;this.json=json;
        write=new TransactionTemplate(manager);write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        read=new TransactionTemplate(manager);read.setReadOnly(true);
    }
    @Override public ConfigVO config(String entity,String store){scope(entity,store);outside();return read.execute(tx->view(entity,store,configRow(entity,store,false)));}
    private record MappingBatch(long endId,List<InventoryFoodStockProjectionPort.Mapping> mappings,List<String> issues) {}
    @Override public Preflight preflight(String entity,String store) {
        scope(entity,store);outside();var issues=new LinkedHashSet<String>();
        var receiver=receivers.getIfUnique();
        if(receiver==null)issues.add("RECEIVER_UNAVAILABLE");
        else try {
            var ready=receiver.validateMappings(new InventoryFoodStockProjectionPort.Scope(entity,store),List.of());
            if(ready==null||!ready.isEmpty())issues.add("RECEIVER_NOT_READY");
        } catch(RuntimeException unavailable){issues.add("RECEIVER_NOT_READY");}
        var unknown=read.execute(tx->balances.selectCount(Wrappers.<FnbStockBalance>lambdaQuery().eq(FnbStockBalance::getTenantNo,"MAIN")
                .eq(FnbStockBalance::getEntityNo,entity).eq(FnbStockBalance::getStoreNo,store)
                .and(q->q.isNull(FnbStockBalance::getTrackingMode).or().isNull(FnbStockBalance::getStatus))));
        if(unknown!=null&&unknown>0)issues.add("UNCONFIGURED_LOCAL_BALANCES");
        long cursor=0;
        while(true) {
            long after=cursor;var batch=read.execute(tx->mappingBatch(entity,store,after));
            if(batch==null||batch.endId()==cursor)break;
            cursor=batch.endId();issues.addAll(batch.issues());
            if(receiver==null||issues.contains("RECEIVER_NOT_READY"))continue;
            try {
                var results=receiver.validateMappings(new InventoryFoodStockProjectionPort.Scope(entity,store),batch.mappings());
                var expected=new HashSet<>(batch.mappings().stream().map(InventoryFoodStockProjectionPort.Mapping::itemNo).toList());
                if(results==null||results.size()!=expected.size())throw new IllegalStateException("Incomplete mapping preflight");
                for(var result:results) {
                    if(result==null||!expected.remove(result.itemNo())||result.valid()!=(result.issueCode()==null))
                        throw new IllegalStateException("Invalid mapping preflight");
                    if(!result.valid())issues.add(result.itemNo()+":"+result.issueCode());
                }
            } catch(RuntimeException unavailable){issues.add("RECEIVER_NOT_READY");}
        }
        return new Preflight(issues.isEmpty(),List.copyOf(issues));
    }
    private MappingBatch mappingBatch(String entity,String store,long after) {
        var rows=mappings.selectList(Wrappers.<FnbStockSyncMapping>lambdaQuery().eq(FnbStockSyncMapping::getTenantNo,"MAIN")
                .eq(FnbStockSyncMapping::getEntityNo,entity).eq(FnbStockSyncMapping::getStoreNo,store)
                .eq(FnbStockSyncMapping::getTargetSystem,"INVENTORY").gt(FnbStockSyncMapping::getId,after)
                .orderByAsc(FnbStockSyncMapping::getId).last("LIMIT 200"));
        var selected=new ArrayList<InventoryFoodStockProjectionPort.Mapping>();var issues=new ArrayList<String>();
        for(var mapping:rows) {
            var source=items.selectOne(Wrappers.<FnbStockItem>lambdaQuery().eq(FnbStockItem::getTenantNo,"MAIN")
                    .eq(FnbStockItem::getEntityNo,entity).eq(FnbStockItem::getItemNo,mapping.getItemNo()));
            if(source==null||!"ACTIVE".equals(mapping.getStatus())||!source.getBaseUnit().equals(mapping.getSourceUnit())
                    ||!source.getBaseUnit().equals(mapping.getTargetUnit())){issues.add(mapping.getItemNo()+":INVALID_SOURCE_MAPPING");continue;}
            selected.add(new InventoryFoodStockProjectionPort.Mapping(mapping.getItemNo(),source.getBaseUnit(),mapping.getTargetOwnerRef(),mapping.getTargetLocationRef(),mapping.getTargetItemRef()));
        }
        return new MappingBatch(rows.isEmpty()?after:rows.getLast().getId(),List.copyOf(selected),List.copyOf(issues));
    }
    @Override public ConfigVO configure(String entity,String store,String actor,ConfigCommand req,String key){
        if(req==null||req.version()<0||req.version()==Long.MAX_VALUE)throw bad();
        if(req.reason()!=null)text(req.reason(),256);
        // Check committed idempotency before mutable network readiness.
        String hash=fingerprint(actor,req);var old=lookup(entity,store,"SYNC_CONFIG",key,hash,ConfigVO.class);if(old!=null)return old;
        if(req.enabled()&&!preflight(entity,store).allowed())throw conflict();
        return command(entity,store,actor,"SYNC_CONFIG",key,hash,req,ConfigVO.class,()->{
            var row=configRow(entity,store,true);
            if(row==null){if(req.version()!=0)throw conflict();row=new FnbStockSyncConfig();audit(row,entity,store,actor);row.setTargetSystem("INVENTORY");row.setStatus("OFF");row.setGeneration(0L);row.setBaselineCursor(0L);row.setBaselineEndId(0L);configs.insert(row);}
            if(row.getVersion()!=req.version())throw conflict();
            if(req.enabled()){
                if(!"OFF".equals(row.getStatus())||row.getGeneration()==Long.MAX_VALUE)throw conflict();
                row.setGeneration(row.getGeneration()+1);row.setStatus("BOOTSTRAPPING");row.setBaselineCursor(0L);row.setEnabledAt(LocalDateTime.now());
                var max=balances.selectObjs(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<FnbStockBalance>()
                        .select("COALESCE(MAX(id),0)").eq("tenant_no","MAIN").eq("entity_no",entity).eq("store_no",store));
                row.setBaselineEndId(Long.parseLong(max.getFirst().toString()));
            }else if(!"OFF".equals(row.getStatus()))row.setStatus("DISABLING");
            row.setUpdatedBy(actor);if(configs.updateById(row)!=1)throw conflict();
            if(!"OFF".equals(row.getStatus()))publisher.control(entity,store,row.getGeneration(),req.enabled()?"ACTIVATE":"DEACTIVATE",row.getVersion());
            log(entity,store,actor,"SYNC_CONFIG",key,req.reason());return view(entity,store,row);
        });}
    @Override public PageData<MappingVO> mappings(String entity,String store,int page,int size){scope(entity,store);paging(page,size);outside();return read.execute(tx->{var p=mappings.selectPage(new Page<>(page,size),Wrappers.<FnbStockSyncMapping>lambdaQuery().eq(FnbStockSyncMapping::getTenantNo,"MAIN").eq(FnbStockSyncMapping::getEntityNo,entity).eq(FnbStockSyncMapping::getStoreNo,store).orderByAsc(FnbStockSyncMapping::getItemNo));return PageData.of(p.getRecords().stream().map(this::view).toList(),p.getTotal(),page,size);});}
    @Override public MappingVO map(String entity,String store,String item,String actor,MappingCommand req,String key){text(item,32);if(req==null||req.version()<0)throw bad();text(req.sourceUnit(),16);text(req.targetUnit(),16);if(!req.sourceUnit().equals(req.targetUnit()))throw bad();
        boolean any=req.targetOwnerRef()!=null||req.targetLocationRef()!=null||req.targetItemRef()!=null;
        if(any){text(req.targetOwnerRef(),64);text(req.targetLocationRef(),64);text(req.targetItemRef(),64);}
        return command(entity,store,actor,"SYNC_MAPPING",key,fingerprint(actor,item,req),req,MappingVO.class,()->{
            // Mapping edits are explicit OFF-only operations; re-enable starts a fresh generation baseline.
            var c=configRow(entity,store,true);if(c!=null&&!"OFF".equals(c.getStatus()))throw conflict();
            var source=items.selectOne(Wrappers.<FnbStockItem>lambdaQuery().eq(FnbStockItem::getTenantNo,"MAIN").eq(FnbStockItem::getEntityNo,entity).eq(FnbStockItem::getItemNo,item));
            if(source==null||!source.getBaseUnit().equals(req.sourceUnit()))throw bad();
            var m=mappings.selectOne(Wrappers.<FnbStockSyncMapping>lambdaQuery().eq(FnbStockSyncMapping::getTenantNo,"MAIN").eq(FnbStockSyncMapping::getEntityNo,entity).eq(FnbStockSyncMapping::getStoreNo,store).eq(FnbStockSyncMapping::getItemNo,item).eq(FnbStockSyncMapping::getTargetSystem,"INVENTORY").last("FOR UPDATE"));
            boolean fresh=m==null;
            if(fresh){if(req.version()!=0)throw conflict();m=new FnbStockSyncMapping();audit(m,entity,store,actor);m.setItemNo(item);m.setTargetSystem("INVENTORY");m.setMappingRevision(1L);}
            else{if(m.getVersion()!=req.version()||m.getMappingRevision()==Long.MAX_VALUE)throw conflict();m.setMappingRevision(m.getMappingRevision()+1);}
            m.setTargetOwnerRef(req.targetOwnerRef());m.setTargetLocationRef(req.targetLocationRef());m.setTargetItemRef(req.targetItemRef());m.setSourceUnit(req.sourceUnit());m.setTargetUnit(req.targetUnit());m.setStatus("ACTIVE");
            if(fresh)mappings.insert(m);else if(mappings.updateById(m)!=1)throw conflict();log(entity,store,actor,"SYNC_MAPPING",key,item);return view(m);
        });}
    @Override public PageData<DeliveryVO> deliveries(String entity,String store,int page,int size,String status,String item){scope(entity,store);paging(page,size);outside();return read.execute(tx->{var p=deliveries.selectPage(new Page<>(page,size),Wrappers.<FnbStockSyncDelivery>lambdaQuery().eq(FnbStockSyncDelivery::getTenantNo,"MAIN").eq(FnbStockSyncDelivery::getEntityNo,entity).eq(FnbStockSyncDelivery::getStoreNo,store).eq(status!=null,FnbStockSyncDelivery::getStatus,status).eq(item!=null,FnbStockSyncDelivery::getItemNo,item).orderByDesc(FnbStockSyncDelivery::getId));return PageData.of(p.getRecords().stream().map(this::view).toList(),p.getTotal(),page,size);});}
    @Override public DeliveryVO retry(String entity,String store,String number,String actor,String key){text(number,32);return command(entity,store,actor,"SYNC_RETRY",key,fingerprint(actor,number),number,DeliveryVO.class,()->{
        var c=configRow(entity,store,true);var d=deliveries.selectOne(Wrappers.<FnbStockSyncDelivery>lambdaQuery().eq(FnbStockSyncDelivery::getTenantNo,"MAIN").eq(FnbStockSyncDelivery::getEntityNo,entity).eq(FnbStockSyncDelivery::getStoreNo,store).eq(FnbStockSyncDelivery::getSyncNo,number).last("FOR UPDATE"));
        if(d==null)throw BizException.of(ErrorCode.NOT_FOUND);if(c==null||c.getGeneration()!=d.getGeneration().longValue()||"OFF".equals(c.getStatus()))throw conflict();
        if("COMPLETED".equals(d.getStatus()))return view(d);if("BLOCKED_MAPPING".equals(d.getStatus()))throw conflict();publisher.retry(d);log(entity,store,actor,"SYNC_RETRY",key,number);return view(d);});}
    private FnbStockSyncConfig configRow(String e,String s,boolean lock){var q=Wrappers.<FnbStockSyncConfig>lambdaQuery().eq(FnbStockSyncConfig::getTenantNo,"MAIN").eq(FnbStockSyncConfig::getEntityNo,e).eq(FnbStockSyncConfig::getStoreNo,s).eq(FnbStockSyncConfig::getTargetSystem,"INVENTORY");if(lock)q.last("FOR UPDATE");return configs.selectOne(q);}
    private ConfigVO view(String e,String s,FnbStockSyncConfig row){long backlog=deliveries.selectCount(Wrappers.<FnbStockSyncDelivery>lambdaQuery().eq(FnbStockSyncDelivery::getTenantNo,"MAIN").eq(FnbStockSyncDelivery::getEntityNo,e).eq(FnbStockSyncDelivery::getStoreNo,s).notIn(FnbStockSyncDelivery::getStatus,List.of("COMPLETED","CANCELLED")));return row==null?new ConfigVO("OFF",0,0,backlog,null):new ConfigVO(row.getStatus(),row.getGeneration(),row.getVersion(),backlog,row.getLastErrorCode());}
    private MappingVO view(FnbStockSyncMapping m){return new MappingVO(m.getItemNo(),m.getTargetOwnerRef(),m.getTargetLocationRef(),m.getTargetItemRef(),m.getSourceUnit(),m.getTargetUnit(),m.getMappingRevision(),m.getStatus(),m.getVersion());}
    private DeliveryVO view(FnbStockSyncDelivery d){return new DeliveryVO(d.getSyncNo(),d.getItemNo(),d.getGeneration(),d.getSourceVersion(),d.getStatus(),d.getAttempts(),d.getLastErrorCode(),d.getAckRef());}
    private <T>T lookup(String e,String s,String action,String key,String hash,Class<T> type){scope(e,s);text(key,128);outside();return read.execute(tx->receipt(e,s,action,key,hash,type));}
    private <T>T receipt(String e,String s,String action,String key,String hash,Class<T> type){var c=commands.selectOne(Wrappers.<FnbStockCommand>lambdaQuery().eq(FnbStockCommand::getTenantNo,"MAIN").eq(FnbStockCommand::getEntityNo,e).eq(FnbStockCommand::getStoreNo,s).eq(FnbStockCommand::getAction,action).eq(FnbStockCommand::getOperationKey,key));if(c==null)return null;if(!hash.equals(c.getRequestHash())||!"COMPLETED".equals(c.getStatus()))throw conflict();return json.readValue(c.getResultJson(),type);}
    private <T>T command(String e,String s,String actor,String action,String key,String hash,Object request,Class<T> type,Supplier<T> work){scope(e,s);text(actor,32);text(key,128);outside();try{return write.execute(tx->{var prior=receipt(e,s,action,key,hash,type);if(prior!=null)return prior;var c=new FnbStockCommand();audit(c,e,s,actor);c.setCommandNo(BizKey.next("FSC"));c.setAction(action);c.setOperationKey(key);c.setRequestHash(hash);c.setRequestSnapshot(json.writeValueAsString(request));c.setStatus("PROCESSING");commands.insert(c);var result=work.get();c.setResultJson(json.writeValueAsString(result));c.setStatus("COMPLETED");if(commands.updateById(c)!=1)throw conflict();return result;});}catch(DuplicateKeyException conflict){throw conflict();}}
    private String fingerprint(Object... args){return FoodStockFingerprint.sha256(json.writeValueAsString(args));}
    private void log(String e,String s,String actor,String action,String key,String reason){var row=new FnbActionLog();audit(row,e,s,actor);row.setActionNo(BizKey.next("FAL"));row.setObjectType("STOCK_SYNC");row.setObjectNo(s);row.setAction(action);row.setOperatorNo(actor);row.setRequestNo(key);row.setReason(reason);logs.insert(row);}
    private static void audit(BaseEntity row,String e,String s,String actor){row.setTenantNo("MAIN");row.setVersion(0L);row.setDeleted(0);row.setCreatedAt(LocalDateTime.now());row.setUpdatedAt(row.getCreatedAt());row.setCreatedBy(actor);row.setUpdatedBy(actor);if(row instanceof FnbStockSyncConfig c){c.setEntityNo(e);c.setStoreNo(s);}if(row instanceof FnbStockSyncMapping m){m.setEntityNo(e);m.setStoreNo(s);}if(row instanceof FnbStockCommand c){c.setEntityNo(e);c.setStoreNo(s);}if(row instanceof FnbActionLog l){l.setEntityNo(e);l.setStoreNo(s);}}
    private static void scope(String e,String s){text(e,32);text(s,32);}
    private static void text(String s,int max){if(s==null||s.isBlank()||s.length()>max||!s.equals(s.trim()))throw bad();}
    private static void paging(int p,int n){if(p<1||n<1||n>100)throw bad();}
    private static void outside(){if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Sync management requires an independent transaction");}
    private static BizException bad(){return BizException.of(ErrorCode.BAD_REQUEST);}
    private static BizException conflict(){return BizException.of(ErrorCode.CONFLICT);}
}
