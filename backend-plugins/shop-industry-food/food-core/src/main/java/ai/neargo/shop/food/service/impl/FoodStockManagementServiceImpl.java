package ai.neargo.shop.food.service.impl;

import ai.neargo.shop.common.*;
import ai.neargo.shop.food.dto.FoodStockContracts.*;
import ai.neargo.shop.food.entity.*;
import ai.neargo.shop.food.mapper.FoodMappers.*;
import ai.neargo.shop.food.policy.FoodCommandFingerprint;
import ai.neargo.shop.food.policy.FoodLocalStockBalance;
import ai.neargo.shop.food.service.FoodStockItemService;
import ai.neargo.shop.food.service.FoodStockBalanceService;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** One transaction boundary for the two management contracts; no independent inventory calls. */
public final class FoodStockManagementServiceImpl implements FoodStockItemService, FoodStockBalanceService {
    private final StockItemMapper items;
    private final StockBalanceMapper balances;
    private final StockCommandMapper commands;
    private final StockMovementMapper movements;
    private final StockReservationItemMapper leaves;
    private final GoodsCatalogQueryPort catalog;
    private final ObjectMapper json;
    private final FoodStockSyncPublisher sync;
    private final TransactionTemplate write;
    private final TransactionTemplate read;

    public FoodStockManagementServiceImpl(StockItemMapper items, StockBalanceMapper balances,
            StockCommandMapper commands, StockMovementMapper movements, StockReservationItemMapper leaves,
            GoodsCatalogQueryPort catalog, ObjectMapper json, PlatformTransactionManager manager,FoodStockSyncPublisher sync) {
        this.items=items; this.balances=balances; this.commands=commands; this.movements=movements;
        this.leaves=leaves; this.catalog=catalog; this.json=json;this.sync=sync;
        write=new TransactionTemplate(manager);
        write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        read=new TransactionTemplate(manager); read.setReadOnly(true);
    }

    @Override public ItemVO createItem(String entity,String store,String actor,CreateItem req,String key) {
        scope(entity,store); text(actor,32); text(key,128); noOuter();
        if(req==null) throw bad();
        text(req.name(),128); text(req.baseUnit(),16); mode(req.trackingMode());
        if(req.weighed()!=null&&!Integer.valueOf(0).equals(req.weighed())&&!Integer.valueOf(1).equals(req.weighed()))throw bad();
        if(!Set.of("MATERIAL","FINISHED").contains(req.itemKind()==null?"":req.itemKind())) throw bad();
        if("FINISHED".equals(req.itemKind()) && req.skuNo()==null) throw bad();
        String hash=hash("CREATE_ITEM",actor,req);
        // A completed receipt survives upstream product unavailability or later configuration changes.
        var prior=read.execute(tx->receipt(entity,store,"CREATE_ITEM",key,hash,ItemVO.class));
        if(prior!=null) return prior;
        if(req.skuNo()!=null) {
            text(req.skuNo(),32);
            var sku=catalog.getSku(entity,store,req.skuNo());
            if(sku==null || !entity.equals(sku.entityNo()) || !store.equals(sku.storeNo())
                    || !req.skuNo().equals(sku.skuNo())) throw bad();
        }
        return command(entity,store,actor,"CREATE_ITEM",key,hash,ItemVO.class,req,()-> {
            var row=new FnbStockItem(); audit(row,actor); row.setEntityNo(entity);
            row.setItemNo(BizKey.next("FSI")); row.setItemKind(req.itemKind()); row.setWeighed(req.weighed()==null?0:req.weighed()); row.setSkuNo(req.skuNo());
            row.setName(req.name()); row.setBaseUnit(req.baseUnit()); row.setTrackingMode(req.trackingMode());
            row.setStatus("ACTIVE"); items.insert(row); return itemView(row);
        });
    }
    @Override public ItemVO updateItem(String entity,String store,String item,String actor,UpdateItem req,String key) {
        if(req==null) throw bad(); text(req.name(),128); mode(req.trackingMode()); status(req.status()); ver(req.version());
        return command(entity,store,actor,"UPDATE_ITEM",key,hash("UPDATE_ITEM",actor,item,req),ItemVO.class,req,()-> {
            var row=item(entity,item,true); expected(row,req.version());
            if(req.weighed()!=null&&!req.weighed().equals(row.getWeighed())){
                if(!Integer.valueOf(0).equals(req.weighed())&&!Integer.valueOf(1).equals(req.weighed()))throw bad();
                if(balances.selectCount(Wrappers.<FnbStockBalance>lambdaQuery().eq(FnbStockBalance::getTenantNo,"MAIN")
                        .eq(FnbStockBalance::getEntityNo,entity).eq(FnbStockBalance::getItemNo,item)
                        .and(q->q.ne(FnbStockBalance::getOnHand,java.math.BigDecimal.ZERO).or().ne(FnbStockBalance::getReservedQty,java.math.BigDecimal.ZERO)))>0
                    ||leaves.selectCount(Wrappers.<FnbStockReservationItem>lambdaQuery().eq(FnbStockReservationItem::getTenantNo,"MAIN").eq(FnbStockReservationItem::getEntityNo,entity).eq(FnbStockReservationItem::getItemNo,item))>0
                    ||movements.selectCount(Wrappers.<FnbStockMovement>lambdaQuery().eq(FnbStockMovement::getTenantNo,"MAIN").eq(FnbStockMovement::getEntityNo,entity).eq(FnbStockMovement::getItemNo,item))>0)throw conflict();
                row.setWeighed(req.weighed());
            }
            if(!"ACTIVE".equals(req.status()) || !row.getTrackingMode().equals(req.trackingMode())) {
                if(leaves.selectCount(Wrappers.<FnbStockReservationItem>lambdaQuery()
                        .eq(FnbStockReservationItem::getTenantNo,"MAIN").eq(FnbStockReservationItem::getEntityNo,entity)
                        .eq(FnbStockReservationItem::getItemNo,item)
                        .apply("issued_qty + released_qty < original_qty"))>0) throw conflict();
            }
            row.setName(req.name());row.setStatus(req.status());row.setTrackingMode(req.trackingMode());
            row.setUpdatedBy(actor); if(items.updateById(row)!=1) throw conflict();
            for(var b:balances.selectList(Wrappers.<FnbStockBalance>lambdaQuery().eq(FnbStockBalance::getTenantNo,"MAIN")
                    .eq(FnbStockBalance::getEntityNo,entity).eq(FnbStockBalance::getItemNo,item).orderByAsc(FnbStockBalance::getStoreNo)))
                sync.capture(entity,b.getStoreNo(),item);
            return itemView(row);
        });
    }
    @Override public ItemVO getItem(String entity,String store,String item) {
        scope(entity,store);noOuter();return read.execute(tx->itemView(item(entity,item,false)));
    }
    @Override public PageData<ItemVO> listItems(String entity,String store,int page,int size,String keyword,String kind,String status) {
        scope(entity,store); paging(page,size);noOuter(); if(keyword!=null)text(keyword,128);
        return read.execute(tx->{var q=Wrappers.<FnbStockItem>lambdaQuery().eq(FnbStockItem::getTenantNo,"MAIN")
                .eq(FnbStockItem::getEntityNo,entity).like(keyword!=null,FnbStockItem::getName,keyword)
                .eq(kind!=null,FnbStockItem::getItemKind,kind).eq(status!=null,FnbStockItem::getStatus,status)
                .orderByAsc(FnbStockItem::getItemNo);
            var p=items.selectPage(new Page<>(page,size),q);
            return PageData.of(p.getRecords().stream().map(this::itemView).toList(),p.getTotal(),page,size);});
    }
    @Override public BalanceVO initialize(String entity,String store,String actor,Initialize req,String key) {
        if(req==null)throw bad();mode(req.trackingMode());status(req.status());reason(req.reasonCode(),req.reasonDetail());
        var qty="TRACKED".equals(req.trackingMode())?quantity(req.initialQty()):BigDecimal.ZERO;
        if("UNTRACKED".equals(req.trackingMode()) && req.initialQty()!=null && req.initialQty().signum()!=0) throw bad();
        return command(entity,store,actor,"INITIALIZE",key,hash("INITIALIZE",actor,req),BalanceVO.class,req,()->{
            var source=item(entity,req.itemNo(),true); if(!"ACTIVE".equals(source.getStatus()))throw conflict();
            if(balances.lockBalance(entity,store,req.itemNo())!=null)throw conflict();
            var row=new FnbStockBalance();audit(row,actor);row.setEntityNo(entity);row.setStoreNo(store);
            row.setBalanceNo(BizKey.next("FSB"));row.setItemNo(req.itemNo());row.setTrackingMode(req.trackingMode());
            row.setStatus(req.status());row.setOnHand(qty);row.setReservedQty(BigDecimal.ZERO);balances.insert(row);
            var cmd=currentCommand(entity,store,"INITIALIZE",key);
            if(qty.signum()>0)movement(entity,store,actor,cmd,row,source.getBaseUnit(),"INITIALIZE",qty,
                    BigDecimal.ZERO,BigDecimal.ZERO,req.reasonCode(),req.reasonDetail());
            sync.capture(entity,store,row.getItemNo());
            return balanceView(row,source.getBaseUnit(),cmd.getCommandNo());
        });
    }
    @Override public BalanceVO adjust(String entity,String store,String item,String actor,Adjust req,String key) {
        if(req==null || req.delta()==null || req.delta().signum()==0)throw bad();ver(req.version());
        quantity(req.delta().abs());reason(req.reasonCode(),req.reasonDetail());
        return command(entity,store,actor,"ADJUST",key,hash("ADJUST",actor,item,req),BalanceVO.class,req,()->{
            var source=item(entity,item,true);var row=balance(entity,store,item);expected(row,req.version());
            usable(source,row); if(!"TRACKED".equals(row.getTrackingMode())) throw conflict();
            var before=row.getOnHand();var reserved=row.getReservedQty();
            try {row.setOnHand(new FoodLocalStockBalance(before,reserved).adjust(req.delta()).onHand());}
            catch(IllegalArgumentException invalid){throw conflict();}
            row.setUpdatedBy(actor);if(balances.updateById(row)!=1)throw conflict();
            var cmd=currentCommand(entity,store,"ADJUST",key);
            movement(entity,store,actor,cmd,row,source.getBaseUnit(),req.delta().signum()>0?"ADJUST_IN":"ADJUST_OUT",
                    req.delta().abs(),before,reserved,req.reasonCode(),req.reasonDetail());
            sync.capture(entity,store,row.getItemNo());
            return balanceView(row,source.getBaseUnit(),cmd.getCommandNo());
        });
    }
    @Override public BalanceVO changePolicy(String entity,String store,String item,String actor,Policy req,String key) {
        if(req==null)throw bad();mode(req.trackingMode());status(req.status());ver(req.version());reason(req.reasonCode(),req.reasonDetail());
        return command(entity,store,actor,"POLICY",key,hash("POLICY",actor,item,req),BalanceVO.class,req,()->{
            var source=item(entity,item,true);var row=balance(entity,store,item);expected(row,req.version());
            if(row.getReservedQty().signum()!=0)throw conflict();
            if(leaves.selectCount(Wrappers.<FnbStockReservationItem>lambdaQuery()
                    .eq(FnbStockReservationItem::getTenantNo,"MAIN").eq(FnbStockReservationItem::getEntityNo,entity)
                    .eq(FnbStockReservationItem::getStoreNo,store).eq(FnbStockReservationItem::getItemNo,item)
                    .apply("issued_qty + released_qty < original_qty"))>0)throw conflict();
            if(!req.trackingMode().equals(row.getTrackingMode()) && row.getOnHand().signum()!=0)throw conflict();
            if(!req.trackingMode().equals(row.getTrackingMode())) {
                var issued=movements.selectObjs(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<FnbStockMovement>()
                        .select("COALESCE(SUM(CASE WHEN action IN ('ISSUE','SUPPLEMENT') THEN quantity WHEN action IN ('RETURN','LOSS') THEN -quantity ELSE 0 END),0)")
                        .eq("tenant_no","MAIN").eq("entity_no",entity).eq("store_no",store).eq("item_no",item));
                if(new BigDecimal(issued.getFirst().toString()).signum()!=0)throw conflict();
            }
            if("UNTRACKED".equals(row.getTrackingMode()) && "TRACKED".equals(req.trackingMode()))throw conflict();
            row.setTrackingMode(req.trackingMode());row.setStatus(req.status());row.setUpdatedBy(actor);
            if(balances.updateById(row)!=1)throw conflict();
            sync.capture(entity,store,row.getItemNo());
            return balanceView(row,source.getBaseUnit(),currentCommand(entity,store,"POLICY",key).getCommandNo());
        });
    }
    @Override public BalanceVO getBalance(String entity,String store,String item) {
        scope(entity,store);noOuter();return read.execute(tx->{var i=item(entity,item,false);
            var row=balances.selectOne(Wrappers.<FnbStockBalance>lambdaQuery().eq(FnbStockBalance::getTenantNo,"MAIN")
                    .eq(FnbStockBalance::getEntityNo,entity).eq(FnbStockBalance::getStoreNo,store).eq(FnbStockBalance::getItemNo,item));
            if(row==null)throw BizException.of(ErrorCode.NOT_FOUND);return balanceView(row,i.getBaseUnit(),null);});
    }
    @Override public PageData<BalanceVO> listBalances(String entity,String store,int page,int size) {
        scope(entity,store);paging(page,size);noOuter();return read.execute(tx->{
            var p=balances.selectPage(new Page<>(page,size),Wrappers.<FnbStockBalance>lambdaQuery()
                    .eq(FnbStockBalance::getTenantNo,"MAIN").eq(FnbStockBalance::getEntityNo,entity)
                    .eq(FnbStockBalance::getStoreNo,store).orderByAsc(FnbStockBalance::getItemNo));
            return PageData.of(p.getRecords().stream().map(b->balanceView(b,item(entity,b.getItemNo(),false).getBaseUnit(),null)).toList(),p.getTotal(),page,size);});
    }
    @Override public PageData<MovementVO> listMovements(String entity,String store,int page,int size,String item,String action,LocalDateTime from,LocalDateTime to) {
        scope(entity,store);paging(page,size);noOuter();if(item!=null)text(item,32);if(action!=null)text(action,32);
        if(from!=null && to!=null && !from.isBefore(to))throw bad();
        return read.execute(tx->{var p=movements.selectPage(new Page<>(page,size),Wrappers.<FnbStockMovement>lambdaQuery()
                .eq(FnbStockMovement::getTenantNo,"MAIN").eq(FnbStockMovement::getEntityNo,entity).eq(FnbStockMovement::getStoreNo,store)
                .eq(item!=null,FnbStockMovement::getItemNo,item).eq(action!=null,FnbStockMovement::getAction,action)
                .ge(from!=null,FnbStockMovement::getCreatedAt,from).lt(to!=null,FnbStockMovement::getCreatedAt,to)
                .orderByDesc(FnbStockMovement::getId));
            return PageData.of(p.getRecords().stream().map(m->new MovementVO(m.getMovementNo(),m.getCommandNo(),m.getItemNo(),m.getReservationNo(),m.getAction(),m.getQuantity(),m.getBaseUnit(),m.getOnHandBefore(),m.getOnHandAfter(),m.getReservedBefore(),m.getReservedAfter(),m.getReasonCode(),m.getReasonDetail(),m.getCreatedBy(),m.getCreatedAt())).toList(),p.getTotal(),page,size);});
    }
    private <T>T command(String entity,String store,String actor,String action,String key,String hash,Class<T> type,Object payload,Supplier<T> work) {
        scope(entity,store);text(actor,32);text(key,128);noOuter();
        try {return write.execute(tx->{var old=receipt(entity,store,action,key,hash,type);if(old!=null)return old;
            var cmd=new FnbStockCommand();audit(cmd,actor);cmd.setEntityNo(entity);cmd.setStoreNo(store);
            cmd.setCommandNo(BizKey.next("FSC"));cmd.setAction(action);cmd.setOperationKey(key);cmd.setRequestHash(hash);
            cmd.setStatus("PROCESSING");cmd.setRequestSnapshot(json.writeValueAsString(new Object[]{actor,action,payload}));commands.insert(cmd);
            var result=work.get();cmd.setStatus("COMPLETED");cmd.setResultJson(json.writeValueAsString(result));
            if(commands.updateById(cmd)!=1)throw conflict();return result;});
        }catch(DuplicateKeyException|ConcurrencyFailureException failure){throw conflict();}
    }
    private <T>T receipt(String entity,String store,String action,String key,String hash,Class<T> type) {
        var cmd=currentCommand(entity,store,action,key);if(cmd==null)return null;
        if(!hash.equals(cmd.getRequestHash()) || !"COMPLETED".equals(cmd.getStatus()) || cmd.getResultJson()==null)throw conflict();
        return json.readValue(cmd.getResultJson(),type);
    }
    private FnbStockCommand currentCommand(String entity,String store,String action,String key) {
        return commands.selectOne(Wrappers.<FnbStockCommand>lambdaQuery().eq(FnbStockCommand::getTenantNo,"MAIN")
                .eq(FnbStockCommand::getEntityNo,entity).eq(FnbStockCommand::getStoreNo,store)
                .eq(FnbStockCommand::getAction,action).eq(FnbStockCommand::getOperationKey,key));
    }
    private FnbStockItem item(String entity,String item,boolean lock) {
        text(item,32);var q=Wrappers.<FnbStockItem>lambdaQuery().eq(FnbStockItem::getTenantNo,"MAIN")
                .eq(FnbStockItem::getEntityNo,entity).eq(FnbStockItem::getItemNo,item);
        if(lock)q.last("FOR UPDATE");var row=items.selectOne(q);
        if(row==null)throw BizException.of(ErrorCode.NOT_FOUND);return row;
    }
    private FnbStockBalance balance(String entity,String store,String item) {
        var row=balances.lockBalance(entity,store,item);if(row==null)throw BizException.of(ErrorCode.NOT_FOUND);return row;
    }
    private ItemVO itemView(FnbStockItem row){return new ItemVO(row.getItemNo(),row.getItemKind(),row.getSkuNo(),row.getName(),row.getBaseUnit(),row.getTrackingMode(),row.getStatus(),row.getVersion(),row.getWeighed());}
    private BalanceVO balanceView(FnbStockBalance row,String unit,String command) {
        boolean tracked="TRACKED".equals(row.getTrackingMode());
        return new BalanceVO(row.getBalanceNo(),row.getItemNo(),unit,row.getTrackingMode(),row.getStatus()==null?"UNCONFIGURED":row.getStatus(),tracked?row.getOnHand():null,tracked?row.getReservedQty():null,tracked?row.getOnHand().subtract(row.getReservedQty()):null,row.getVersion(),command);
    }
    private void movement(String entity,String store,String actor,FnbStockCommand cmd,FnbStockBalance row,String unit,String action,BigDecimal qty,BigDecimal before,BigDecimal reservedBefore,String reason,String detail) {
        var m=new FnbStockMovement();audit(m,actor);m.setEntityNo(entity);m.setStoreNo(store);
        m.setMovementNo(BizKey.next("FSM"));m.setCommandNo(cmd.getCommandNo());m.setLineRef(row.getItemNo());
        m.setItemNo(row.getItemNo());m.setAction(action);m.setQuantity(qty);m.setBaseUnit(unit);
        m.setOnHandBefore(before);m.setOnHandAfter(row.getOnHand());m.setReservedBefore(reservedBefore);m.setReservedAfter(row.getReservedQty());
        m.setReasonCode(reason);m.setReasonDetail(detail);movements.insert(m);
    }
    private static void audit(BaseEntity row,String actor){row.setTenantNo("MAIN");row.setVersion(0L);row.setDeleted(0);row.setCreatedBy(actor);row.setUpdatedBy(actor);row.setCreatedAt(LocalDateTime.now());row.setUpdatedAt(row.getCreatedAt());}
    private String hash(String action,Object... values){return FoodCommandFingerprint.hash(action,json.writeValueAsString(values));}
    private static BigDecimal quantity(BigDecimal q){try{return new FoodLocalStockBalance(q,BigDecimal.ZERO).onHand();}catch(IllegalArgumentException invalid){throw bad();}}
    private static void usable(FnbStockItem i,FnbStockBalance b){if(!"ACTIVE".equals(i.getStatus()) || !"ACTIVE".equals(b.getStatus()))throw conflict();}
    private static void expected(BaseEntity row,long version){if(row.getVersion()==null||row.getVersion()!=version||version==Long.MAX_VALUE)throw conflict();}
    private static void ver(Long version){if(version==null||version<0||version==Long.MAX_VALUE)throw bad();}
    private static void reason(String reason,String detail){text(reason,32);if(detail!=null)text(detail,256);}
    private static void mode(String mode){if(!"TRACKED".equals(mode)&&!"UNTRACKED".equals(mode))throw bad();}
    private static void status(String status){if(!"ACTIVE".equals(status)&&!"DISABLED".equals(status))throw bad();}
    private static void scope(String entity,String store){text(entity,32);text(store,32);}
    private static void text(String value,int max){if(value==null||value.isBlank()||value.length()>max||!value.equals(value.trim()))throw bad();}
    private static void paging(int page,int size){if(page<1||size<1||size>100)throw bad();}
    private static void noOuter(){if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Stock management requires its own transaction boundary");}
    private static BizException bad(){return BizException.of(ErrorCode.BAD_REQUEST);}
    private static BizException conflict(){return BizException.of(ErrorCode.CONFLICT);}
}
