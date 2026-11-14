package ai.neargo.shop.food.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.*;
import ai.neargo.shop.food.entity.*;
import ai.neargo.shop.food.mapper.FoodMappers.*;
import ai.neargo.shop.food.service.FoodReturnStockService;
import ai.neargo.shop.spi.food.FoodLocalStockPort;
import ai.neargo.shop.spi.food.FoodStockFingerprint;
import ai.neargo.shop.spi.inventory.InventoryProductionPort;
import ai.neargo.shop.spi.inventory.InventoryProductionFacts;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Durable claim -> native atomic disposition -> durable receipt. No network call holds FOOD locks. */
public final class FoodReturnStockServiceImpl implements FoodReturnStockService {
    private final ReturnStockDispositionMapper claims;private final ReturnMapper returns;private final ReturnItemMapper returnItems;
    private final ProductionConsumptionMapper production;private final ServiceEventMapper events;private final KitchenTaskMapper tasks;
    private final OrderBatchMapper batches;private final OrderLineMapper lines;private final StockCommandMapper commands;
    private final StockReservationMapper reservations;private final StockMovementMapper movements;
    private final FoodLocalStockPort stock;private final ObjectMapper json;private final TransactionTemplate write;private final TransactionTemplate read;
    public record NativeCall(FoodLocalStockPort.Command command,String reservationNo,List<FoodLocalStockPort.ReturnTarget> targets) {}
    public FoodReturnStockServiceImpl(ReturnStockDispositionMapper claims,ReturnMapper returns,ReturnItemMapper returnItems,
            ProductionConsumptionMapper production,ServiceEventMapper events,KitchenTaskMapper tasks,OrderBatchMapper batches,
            OrderLineMapper lines,StockCommandMapper commands,StockReservationMapper reservations,StockMovementMapper movements,
            FoodLocalStockPort stock,ObjectMapper json,PlatformTransactionManager manager) {
        this.claims=claims;this.returns=returns;this.returnItems=returnItems;this.production=production;this.events=events;
        this.tasks=tasks;this.batches=batches;this.lines=lines;this.commands=commands;this.reservations=reservations;
        this.movements=movements;this.stock=stock;this.json=json;write=new TransactionTemplate(manager);
        write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);read=new TransactionTemplate(manager);read.setReadOnly(true);
    }
    @Override public DispositionVO apply(String entity,String store,String number,String actor,Apply input,String key) {
        outside();scope(entity,store);text(number,32);text(actor,32);text(key,128);validate(input);
        String hash=FoodStockFingerprint.sha256(json.writeValueAsString(new Object[]{number,actor,input}));
        FnbReturnStockDisposition row;
        try { row=tx(()->{
            var head=returns.selectOne(Wrappers.<FnbReturn>lambdaQuery().eq(FnbReturn::getTenantNo,"MAIN")
                    .eq(FnbReturn::getEntityNo,entity).eq(FnbReturn::getStoreNo,store).eq(FnbReturn::getReturnNo,number).last("FOR UPDATE"));
            if(head==null)throw missing();
            var old=claims.selectOne(Wrappers.<FnbReturnStockDisposition>lambdaQuery().eq(FnbReturnStockDisposition::getTenantNo,"MAIN")
                    .eq(FnbReturnStockDisposition::getEntityNo,entity).eq(FnbReturnStockDisposition::getStoreNo,store)
                    .eq(FnbReturnStockDisposition::getOperationKey,key));
            if(old!=null){if(!hash.equals(old.getRequestHash()))throw conflict();return old;}
            if(!"HANDLED".equals(head.getKitchenStatus())||Set.of("REJECTED","CANCELLED","CANCEL_PENDING").contains(head.getStatus()))throw conflict();
            var item=returnItems.selectOne(Wrappers.<FnbReturnItem>lambdaQuery().eq(FnbReturnItem::getTenantNo,"MAIN")
                    .eq(FnbReturnItem::getEntityNo,entity).eq(FnbReturnItem::getReturnNo,number)
                    .eq(FnbReturnItem::getReturnItemNo,input.returnItemNo()).last("FOR UPDATE"));
            if(item==null)throw missing();
            var claimed=claims.selectList(Wrappers.<FnbReturnStockDisposition>lambdaQuery().eq(FnbReturnStockDisposition::getTenantNo,"MAIN")
                    .eq(FnbReturnStockDisposition::getEntityNo,entity).eq(FnbReturnStockDisposition::getStoreNo,store)
                    .eq(FnbReturnStockDisposition::getReturnItemNo,item.getReturnItemNo())).stream()
                    .map(FnbReturnStockDisposition::getDishQty).reduce(BigDecimal.ZERO,BigDecimal::add);
            if(claimed.add(input.dishQty()).compareTo(item.getQty())>0)throw conflict();
            var event=events.selectOne(Wrappers.<FnbServiceEvent>lambdaQuery().eq(FnbServiceEvent::getTenantNo,"MAIN")
                    .eq(FnbServiceEvent::getEntityNo,entity).eq(FnbServiceEvent::getEventNo,input.productionEventNo()));
            if(event==null||!"START".equals(event.getAction())||event.getQty()==null||input.dishQty().compareTo(event.getQty())>0)throw conflict();
            var task=tasks.selectOne(Wrappers.<FnbKitchenTask>lambdaQuery().eq(FnbKitchenTask::getTenantNo,"MAIN")
                    .eq(FnbKitchenTask::getEntityNo,entity).eq(FnbKitchenTask::getTaskNo,event.getTaskNo()));
            if(task==null||!item.getFoodLineNo().equals(task.getFoodLineNo())||!Objects.equals(item.getComponentNo(),task.getComponentNo()))throw conflict();
            var line=lines.selectOne(Wrappers.<FnbOrderLine>lambdaQuery().eq(FnbOrderLine::getTenantNo,"MAIN")
                    .eq(FnbOrderLine::getEntityNo,entity).eq(FnbOrderLine::getFoodLineNo,item.getFoodLineNo()).eq(FnbOrderLine::getBatchNo,head.getBatchNo()));
            var batch=batches.selectOne(Wrappers.<FnbOrderBatch>lambdaQuery().eq(FnbOrderBatch::getTenantNo,"MAIN")
                    .eq(FnbOrderBatch::getEntityNo,entity).eq(FnbOrderBatch::getStoreNo,store).eq(FnbOrderBatch::getBatchNo,head.getBatchNo()));
            if(line==null||batch==null)throw conflict();
            var original=production.selectOne(Wrappers.<FnbProductionConsumption>lambdaQuery().eq(FnbProductionConsumption::getTenantNo,"MAIN")
                    .eq(FnbProductionConsumption::getEntityNo,entity).eq(FnbProductionConsumption::getStoreNo,store)
                    .eq(FnbProductionConsumption::getSourceEventNo,input.productionEventNo()).last("FOR UPDATE"));
            if(original==null||!"CONFIRMED".equals(original.getStatus()))throw conflict();
            var eventClaims=claims.selectList(Wrappers.<FnbReturnStockDisposition>lambdaQuery().eq(FnbReturnStockDisposition::getTenantNo,"MAIN")
                    .eq(FnbReturnStockDisposition::getEntityNo,entity).eq(FnbReturnStockDisposition::getStoreNo,store)
                    .eq(FnbReturnStockDisposition::getProductionEventNo,input.productionEventNo())).stream()
                    .map(FnbReturnStockDisposition::getDishQty).reduce(BigDecimal.ZERO,BigDecimal::add);
            if(eventClaims.add(input.dishQty()).compareTo(event.getQty())>0)throw conflict();
            var source=json.readValue(original.getRequestSnapshot(),InventoryProductionPort.Command.class);
            var receipt=json.readValue(original.getReceiptSnapshot(),InventoryProductionPort.Receipt.class);
            ProductionConsumptionServiceImpl.validate(source,receipt);
            if(source.planProof()==null||!InventoryProductionFacts.hash(source).equals(original.getRequestHash())||receipt.issuedLines().isEmpty())throw conflict();
            var nativeCommand=commands.selectOne(Wrappers.<FnbStockCommand>lambdaQuery().eq(FnbStockCommand::getTenantNo,"MAIN")
                    .eq(FnbStockCommand::getEntityNo,entity).eq(FnbStockCommand::getStoreNo,store)
                    .eq(FnbStockCommand::getCommandNo,receipt.documentNo()));
            if(nativeCommand==null||!"COMPLETED".equals(nativeCommand.getStatus())
                    ||!Set.of("ISSUE","ISSUE_DELTA","SUPPLEMENT").contains(nativeCommand.getAction()))throw conflict();
            var nativeReceipt=json.readValue(nativeCommand.getResultJson(),FoodLocalStockPort.Receipt.class);
            if(!"FOOD_LOCAL".equals(nativeReceipt.owner())||!receipt.documentNo().equals(nativeReceipt.commandNo()))throw conflict();
            var reservation=reservations.selectOne(Wrappers.<FnbStockReservation>lambdaQuery().eq(FnbStockReservation::getTenantNo,"MAIN")
                    .eq(FnbStockReservation::getEntityNo,entity).eq(FnbStockReservation::getStoreNo,store)
                    .eq(FnbStockReservation::getReservationNo,nativeReceipt.reservationNo()));
            if(reservation==null||!source.planProof().planHash().equals(reservation.getSourcePlanHash()))throw conflict();
            String operation=BizKey.next("FRSD");var targets=new ArrayList<FoodLocalStockPort.ReturnTarget>();
            for(var issued:receipt.issuedLines()) {
                var movement=movements.selectOne(Wrappers.<FnbStockMovement>lambdaQuery().eq(FnbStockMovement::getTenantNo,"MAIN")
                        .eq(FnbStockMovement::getEntityNo,entity).eq(FnbStockMovement::getStoreNo,store)
                        .eq(FnbStockMovement::getMovementNo,issued.ledgerRef()));
                if(movement==null||!nativeCommand.getCommandNo().equals(movement.getCommandNo())
                        ||!nativeReceipt.movementRefs().contains(movement.getMovementNo())||!issued.itemRef().equals(movement.getItemNo())
                        ||!issued.leafRef().equals(movement.getLineRef())||issued.quantity().compareTo(movement.getQuantity())!=0)throw conflict();
                BigDecimal quantity;
                try{quantity=issued.quantity().multiply(input.dishQty()).divide(event.getQty(),6,RoundingMode.UNNECESSARY);}
                catch(ArithmeticException precision){throw bad();}
                if(quantity.signum()<=0)throw bad();
                targets.add(new FoodLocalStockPort.ReturnTarget(issued.leafRef(),issued.ledgerRef(),quantity,operation,input.disposition(),
                        input.reasonCode(),input.reason()+" [evidence:"+input.evidenceRef()+"]",actor,false,true));
            }
            var raw=new FoodLocalStockPort.Command(operation,"",reservation.getSourceSystem(),reservation.getSourceNo(),reservation.getSourceVersion(),reservation.getLifecycleVersion());
            String nativeHash=FoodStockFingerprint.request(json,"RETURN",raw,List.of(reservation.getReservationNo(),targets));
            var call=new NativeCall(new FoodLocalStockPort.Command(raw.operationKey(),nativeHash,raw.sourceSystem(),raw.sourceNo(),raw.sourceVersion(),raw.lifecycleVersion()),reservation.getReservationNo(),targets);
            var claim=new FnbReturnStockDisposition();claim.setTenantNo("MAIN");claim.setEntityNo(entity);claim.setStoreNo(store);
            claim.setOperationNo(operation);claim.setReturnNo(number);claim.setReturnItemNo(item.getReturnItemNo());
            claim.setProductionEventNo(input.productionEventNo());claim.setDishQty(input.dishQty());claim.setDisposition(input.disposition());
            claim.setOperationKey(key);claim.setRequestHash(hash);claim.setRequestSnapshot(json.writeValueAsString(input));
            claim.setNativeSnapshot(json.writeValueAsString(call));claim.setStatus("PENDING");claim.setVersion(0L);claim.setDeleted(0);
            claim.setCreatedAt(LocalDateTime.now());claim.setUpdatedAt(claim.getCreatedAt());claim.setCreatedBy(actor);claim.setUpdatedBy(actor);claims.insert(claim);return claim;
        }); } catch (DuplicateKeyException race) {
            row=read.execute(t->claims.selectOne(Wrappers.<FnbReturnStockDisposition>lambdaQuery()
                    .eq(FnbReturnStockDisposition::getTenantNo,"MAIN")
                    .eq(FnbReturnStockDisposition::getEntityNo,entity).eq(FnbReturnStockDisposition::getStoreNo,store)
                    .eq(FnbReturnStockDisposition::getOperationKey,key)));
            if(row==null||!hash.equals(row.getRequestHash()))throw conflict();
        }
        return execute(row);
    }
    private DispositionVO execute(FnbReturnStockDisposition row) {
        if("COMPLETED".equals(row.getStatus()))return view(row);
        var call=json.readValue(row.getNativeSnapshot(),NativeCall.class);
        try {
            var lookup=stock.findCommand(new FoodLocalStockPort.Scope(row.getEntityNo(),row.getStoreNo()),"RETURN",call.command().operationKey(),call.command().requestHash());
            if(lookup==null||!Set.of("COMPLETED","NOT_FOUND").contains(lookup.status()))throw conflict();
            var receipt="COMPLETED".equals(lookup.status())?lookup.receipt():stock.returnIssued(new FoodLocalStockPort.Scope(row.getEntityNo(),row.getStoreNo()),call.command(),call.reservationNo(),call.targets());
            if(receipt==null||!"FOOD_LOCAL".equals(receipt.owner())||!call.reservationNo().equals(receipt.reservationNo())||receipt.commandNo()==null)throw conflict();
            return tx(()->{var current=claim(row.getEntityNo(),row.getStoreNo(),row.getReturnNo(),row.getOperationNo());
                if(!"COMPLETED".equals(current.getStatus())){current.setStatus("COMPLETED");current.setStockCommandNo(receipt.commandNo());
                    current.setResultSnapshot(json.writeValueAsString(receipt));current.setLastErrorCode(null);if(claims.updateById(current)!=1)throw conflict();}return view(current);});
        } catch(RuntimeException unknown) {
            return tx(()->{var current=claim(row.getEntityNo(),row.getStoreNo(),row.getReturnNo(),row.getOperationNo());
                if(!"COMPLETED".equals(current.getStatus())){current.setStatus("UNKNOWN");current.setLastErrorCode("STOCK_DISPOSITION_UNCONFIRMED");claims.updateById(current);}return view(current);});
        }
    }
    @Override public DispositionVO retry(String e,String s,String number,String operation,String actor) {
        outside();scope(e,s);text(actor,32);text(number,32);text(operation,32);
        return execute(read.execute(tx->claim(e,s,number,operation)));
    }
    @Override public PageData<DispositionVO> list(String e,String s,String number,int page,int size) {
        outside();scope(e,s);text(number,32);if(page<1||size<1||size>100)throw bad();
        return read.execute(tx->{var p=claims.selectPage(new Page<>(page,size),Wrappers.<FnbReturnStockDisposition>lambdaQuery()
                .eq(FnbReturnStockDisposition::getTenantNo,"MAIN").eq(FnbReturnStockDisposition::getEntityNo,e).eq(FnbReturnStockDisposition::getStoreNo,s)
                .eq(FnbReturnStockDisposition::getReturnNo,number).orderByAsc(FnbReturnStockDisposition::getId));return PageData.of(p.getRecords().stream().map(this::view).toList(),p.getTotal(),page,size);});
    }
    @Override public int recover(int limit) {
        outside();if(limit<1||limit>200)throw bad();int count=0;
        var pending=DataScopeContext.executeWithoutScope(()->claims.selectList(Wrappers.<FnbReturnStockDisposition>lambdaQuery()
                .in(FnbReturnStockDisposition::getStatus,List.of("PENDING","UNKNOWN")).orderByAsc(FnbReturnStockDisposition::getUpdatedAt).last("LIMIT "+limit)));
        for(var row:pending)try{if("COMPLETED".equals(DataScopeContext.executeWithoutScope(()->execute(row)).status()))count++;}catch(RuntimeException retryLater){}
        return count;
    }
    private FnbReturnStockDisposition claim(String e,String s,String number,String operation) {
        var row=claims.selectOne(Wrappers.<FnbReturnStockDisposition>lambdaQuery().eq(FnbReturnStockDisposition::getTenantNo,"MAIN")
                .eq(FnbReturnStockDisposition::getEntityNo,e).eq(FnbReturnStockDisposition::getStoreNo,s)
                .eq(FnbReturnStockDisposition::getReturnNo,number).eq(FnbReturnStockDisposition::getOperationNo,operation));
        if(row==null)throw missing();return row;
    }
    private DispositionVO view(FnbReturnStockDisposition row){return new DispositionVO(row.getOperationNo(),row.getReturnNo(),row.getReturnItemNo(),
            row.getProductionEventNo(),row.getDishQty(),row.getDisposition(),row.getStatus(),row.getStockCommandNo(),row.getLastErrorCode());}
    private <T>T tx(Supplier<T> work){return write.execute(tx->work.get());}
    private static void validate(Apply input){if(input==null)throw bad();text(input.returnItemNo(),32);text(input.productionEventNo(),32);
        if(input.dishQty()==null||input.dishQty().signum()<=0||input.dishQty().stripTrailingZeros().scale()>6)throw bad();
        if(!Set.of("RESALE","LOSS").contains(input.disposition()==null?"":input.disposition()))throw bad();
        text(input.reasonCode(),32);text(input.reason(),128);text(input.evidenceRef(),96);}
    private static void scope(String e,String s){text(e,32);text(s,32);}
    private static void text(String value,int max){if(value==null||value.isBlank()||value.length()>max||!value.equals(value.trim()))throw bad();}
    private static void outside(){if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Return stock execution must not hold business locks");}
    private static BizException bad(){return BizException.of(ErrorCode.BAD_REQUEST);}
    private static BizException conflict(){return BizException.of(ErrorCode.CONFLICT);}
    private static BizException missing(){return BizException.of(ErrorCode.NOT_FOUND);}
}
