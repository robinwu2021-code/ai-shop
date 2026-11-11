package ai.neargo.shop.product.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BaseEntity;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.idem.IdempotencyScope;
import ai.neargo.shop.product.entity.PrdStockReplenishment;
import ai.neargo.shop.product.entity.PrdStockReplenishmentManual;
import ai.neargo.shop.product.entity.PrdStockReplenishmentMovement;
import ai.neargo.shop.product.entity.PrdStockReplenishmentReturn;
import ai.neargo.shop.product.entity.PrdStockWriter;
import ai.neargo.shop.product.mapper.ProductMappers.StockReplenishmentManualMapper;
import ai.neargo.shop.product.mapper.ProductMappers.StockReplenishmentMapper;
import ai.neargo.shop.product.mapper.ProductMappers.StockReplenishmentMovementMapper;
import ai.neargo.shop.product.mapper.ProductMappers.StockReplenishmentReturnMapper;
import ai.neargo.shop.product.service.RetailStockReplenishmentService;
import ai.neargo.shop.product.service.StockReplenishmentAdmissionService;
import ai.neargo.shop.product.service.StockReplenishmentAdmissionService.Mapping;
import ai.neargo.shop.product.service.support.RetailStockReplenishmentLedger;
import ai.neargo.shop.product.service.support.StockReplenishmentCommands;
import ai.neargo.shop.spi.product.LocalStockControlPort.Scope;
import ai.neargo.shop.spi.product.RetailReplenishmentTargetPort;
import ai.neargo.shop.spi.product.RetailReplenishmentTargetPort.Target;
import ai.neargo.shop.spi.product.StockReplenishmentClaim;
import ai.neargo.shop.spi.product.StockReplenishmentSourcePort.Inbound;
import ai.neargo.shop.spi.product.StockReplenishmentSourcePort.Outbound;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.validation.annotation.Validated;
import tools.jackson.databind.ObjectMapper;

/** Retail handover quantities remain in the original PLATFORM counters, with a permanent command receipt. */
@Service
@Validated
public class RetailStockReplenishmentServiceImpl implements RetailStockReplenishmentService {
    private final StockReplenishmentMapper originals;
    private final StockReplenishmentMovementMapper movements;
    private final StockReplenishmentManualMapper manuals;
    private final StockReplenishmentReturnMapper returns;
    private final StockReplenishmentCommands commands;
    private final RetailStockReplenishmentLedger ledger;
    private final RetailReplenishmentTargetPort targets;
    private final ObjectMapper json;
    private final TransactionTemplate reads;

    public RetailStockReplenishmentServiceImpl(StockReplenishmentMapper originals,
            StockReplenishmentMovementMapper movements, StockReplenishmentManualMapper manuals,
            StockReplenishmentReturnMapper returns, StockReplenishmentCommands commands,
            RetailStockReplenishmentLedger ledger, RetailReplenishmentTargetPort targets, ObjectMapper json,
            @Qualifier("transactionManager") PlatformTransactionManager manager) {
        this.originals=originals;this.movements=movements;this.manuals=manuals;this.returns=returns;
        this.commands=commands;this.ledger=ledger;this.targets=targets;this.json=json;
        reads=new TransactionTemplate(manager);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Override public View get(Scope s,String no) {
        boundary(s);return DataScopeContext.executeWithoutScope(()->view(head(s,no,false)));
    }

    @Override public View receive(Scope s,String actor,String key,String no,int cumulative) {
        String hash=IdempotencyScope.requestHash(actor,"RECEIVE",no,Integer.toString(cumulative));
        return commands.execute(s,actor,"RECEIVE",key,hash,View.class,writer->{
            var h=head(s,no,true);int previous=h.getReceivedQuantity().intValueExact();
            if(cumulative<previous||BigDecimal.valueOf(cumulative).add(h.getDifferenceQuantity()).compareTo(h.getExpectedQuantity())>0)throw conflict();
            if(cumulative==previous)return view(h);
            if("CLOSED".equals(h.getStatus()))throw conflict();local(writer);
            ledger.apply(s,actor,"RECEIVE",key,no,target(h),cumulative-previous,"RECEIVE",null);
            h.setReceivedQuantity(BigDecimal.valueOf(cumulative));
            h.setStatus(h.getReceivedQuantity().compareTo(h.getExpectedQuantity())==0?"RECEIVED":"PARTIAL");
            save(h,actor);return view(h);
        });
    }

    @Override public View close(Scope s,String actor,String key,String no,String reason) {
        String hash=IdempotencyScope.requestHash(actor,"CLOSE",no,reason);
        return commands.execute(s,actor,"CLOSE",key,hash,View.class,writer->{
            var h=head(s,no,true);if("CLOSED".equals(h.getStatus()))return view(h);
            h.setDifferenceQuantity(h.getExpectedQuantity().subtract(h.getReceivedQuantity()));
            h.setStatus("CLOSED");h.setCloseReason(reason);save(h,actor);return view(h);
        });
    }

    @Override public View dispatchReturn(Scope s,String actor,String key,String no,int cumulative,String reason) {
        String hash=IdempotencyScope.requestHash(actor,"RETURN",no,Integer.toString(cumulative),reason);
        return commands.execute(s,actor,"RETURN",key,hash,View.class,writer->{
            var h=head(s,no,true);int previous=h.getReturnedQuantity().intValueExact();
            if(cumulative<previous||BigDecimal.valueOf(cumulative).compareTo(h.getReceivedQuantity())>0)throw conflict();
            if(cumulative==previous)return view(h);local(writer);
            ledger.apply(s,actor,"RETURN",key,no,target(h),previous-cumulative,"RETURN",reason);
            h.setReturnedQuantity(BigDecimal.valueOf(cumulative));save(h,actor);return view(h);
        });
    }

    @Override public View ackReturn(Scope s,String actor,String key,String no,Inbound source) {
        String hash=IdempotencyScope.requestHash(actor,"RETURN_ACK",no,source.documentNo(),Integer.toString(source.lineNo()));
        return commands.execute(s,actor,"RETURN_ACK",key,hash,View.class,writer->{
            var h=head(s,no,true);var outbound=json.readValue(h.getSourceSnapshot(),Outbound.class);
            if(!h.getSourceSystem().equals(source.sourceSystem())||!h.getSourceOwner().equals(source.sourceOwner())
                    ||!no.equals(source.sourceRef())||!outbound.itemNo().equals(source.itemNo())
                    ||!outbound.baseUnit().equals(source.baseUnit()))throw conflict();
            int quantity=converted(source.quantity(),mapping(h).factor());
            String proof=IdempotencyScope.requestHash(source.sourceSystem(),source.sourceOwner(),source.documentNo(),
                    Integer.toString(source.lineNo()),source.itemNo(),source.baseUnit(),
                    source.quantity().stripTrailingZeros().toPlainString(),source.sourceRef());
            var old=returns.selectOne(Wrappers.<PrdStockReplenishmentReturn>lambdaQuery()
                    .eq(PrdStockReplenishmentReturn::getTenantNo,"MAIN")
                    .eq(PrdStockReplenishmentReturn::getSourceSystem,source.sourceSystem())
                    .eq(PrdStockReplenishmentReturn::getSourceOwner,source.sourceOwner())
                    .eq(PrdStockReplenishmentReturn::getSourceDocument,source.documentNo())
                    .eq(PrdStockReplenishmentReturn::getSourceLine,source.lineNo()));
            if(old!=null){if(!no.equals(old.getOriginalNo())||!proof.equals(old.getProofHash()))throw conflict();return view(h);}
            var accepted=h.getReturnAcceptedQuantity().add(BigDecimal.valueOf(quantity));
            if(accepted.compareTo(h.getReturnedQuantity())>0)throw conflict();
            var row=new PrdStockReplenishmentReturn();audit(row,s,actor);row.setOriginalNo(no);
            row.setSourceSystem(source.sourceSystem());row.setSourceOwner(source.sourceOwner());
            row.setSourceDocument(source.documentNo());row.setSourceLine(source.lineNo());row.setAcceptedQuantity(quantity);
            row.setProofHash(proof);row.setSourceSnapshot(json.writeValueAsString(source));returns.insert(row);
            h.setReturnAcceptedQuantity(accepted);save(h,actor);return view(h);
        });
    }

    @Override public AdjustmentResult adjust(Scope s,String actor,String key,Adjustment request) {
        String hash=IdempotencyScope.requestHash(actor,"ADJUST",json.writeValueAsString(request));
        return commands.execute(s,actor,"ADJUST",key,hash,AdjustmentResult.class,writer->{
            local(writer);
            if(!request.itemNo().equals(request.target().skuNo())||!request.baseUnit().equals(request.target().baseUnit()))throw conflict();
            var movement=ledger.apply(s,actor,"ADJUST",key,null,request.target(),request.delta(),
                    request.delta()>0?"ADJUST_IN":"ADJUST_OUT",request.reason());
            return new AdjustmentResult(movement.getMovementNo(),request.target(),request.delta(),
                    movement.getOnHandBefore(),movement.getOnHandAfter(),movement.getReservedAfter());
        });
    }

    @Override public View adoptManual(Scope s,String actor,String key,String no,String movementNo) {
        String hash=IdempotencyScope.requestHash(actor,"MANUAL",no,movementNo);
        return commands.execute(s,actor,"MANUAL",key,hash,View.class,writer->{
            var h=head(s,no,true);
            var movement=movements.selectOne(Wrappers.<PrdStockReplenishmentMovement>lambdaQuery()
                    .eq(PrdStockReplenishmentMovement::getTenantNo,"MAIN")
                    .eq(PrdStockReplenishmentMovement::getEntityNo,s.entityNo())
                    .eq(PrdStockReplenishmentMovement::getStoreNo,s.storeNo())
                    .eq(PrdStockReplenishmentMovement::getMovementNo,movementNo));
            if(movement==null||!"ADJUST_IN".equals(movement.getKind())||!"ADJUST".equals(movement.getCommandAction())
                    ||!movement.getItemNo().equals(mapping(h).itemNo())||!movement.getBaseUnit().equals(mapping(h).baseUnit())
                    ||!target(h).identity().equals(movement.getTargetIdentity()))throw conflict();
            var evidence=commands.evidence(s,"ADJUST",movement.getOperationKey(),AdjustmentResult.class);
            if(evidence==null||!movementNo.equals(evidence.movementNo())||evidence.delta()!=movement.getDelta()
                    ||!evidence.target().identity().equals(movement.getTargetIdentity()))throw conflict();
            var old=manuals.selectOne(Wrappers.<PrdStockReplenishmentManual>lambdaQuery()
                    .eq(PrdStockReplenishmentManual::getTenantNo,"MAIN").eq(PrdStockReplenishmentManual::getMovementNo,movementNo));
            if(old!=null){if(!no.equals(old.getOriginalNo()))throw conflict();return view(h);}
            var received=h.getReceivedQuantity().add(BigDecimal.valueOf(movement.getDelta()));
            if(received.compareTo(h.getExpectedQuantity())>0)throw conflict();
            var link=new PrdStockReplenishmentManual();audit(link,s,actor);link.setOriginalNo(no);
            link.setMovementNo(movementNo);link.setQuantity(movement.getDelta());manuals.insert(link);
            h.setReceivedQuantity(received);
            if("CLOSED".equals(h.getStatus()))h.setDifferenceQuantity(h.getExpectedQuantity().subtract(received));
            else h.setStatus(received.compareTo(h.getExpectedQuantity())==0?"RECEIVED":"PARTIAL");
            save(h,actor);return view(h);
        });
    }

    @Override public Reconciliation reconcile(Scope s,String no,Outbound source) {
        boundary(s);
        return DataScopeContext.executeWithoutScope(()->reads.execute(tx->{
            var h=head(s,no,true);var issues=new ArrayList<String>();
            var receipts=movements.total(s.entityNo(),s.storeNo(),no,"RECEIVE");
            var manual=manuals.total(s.entityNo(),s.storeNo(),no);
            var returned=movements.total(s.entityNo(),s.storeNo(),no,"RETURN").negate();
            var accepted=returns.total(s.entityNo(),s.storeNo(),no);
            if(receipts.add(manual).compareTo(h.getReceivedQuantity())!=0)issues.add("RECEIPT_LEDGER_MISMATCH");
            if(returned.compareTo(h.getReturnedQuantity())!=0)issues.add("RETURN_LEDGER_MISMATCH");
            if(accepted.compareTo(h.getReturnAcceptedQuantity())!=0)issues.add("RETURN_ACK_MISMATCH");
            var m=mapping(h);
            if(source==null)issues.add("SOURCE_UNVERIFIED");
            else if(!StockReplenishmentClaim.binding(s,"PLATFORM",source,m.itemNo(),m.baseUnit(),m.factor())
                    .equals(h.getBindingHash()))issues.add("SOURCE_CHANGED");
            var current=targets.requireCurrent(s,new RetailReplenishmentTargetPort.Mapping(m.itemNo(),m.baseUnit(),
                    BigDecimal.ONE,BigDecimal.ONE),target(h));
            if(!current.ready())issues.add("TARGET_UNAVAILABLE");
            if(h.getReceivedQuantity().add(h.getDifferenceQuantity()).compareTo(h.getExpectedQuantity())<0)issues.add("RECEIPT_PENDING");
            if(h.getDifferenceQuantity().signum()>0)issues.add("RECEIPT_DIFFERENCE");
            if(h.getReturnedQuantity().compareTo(h.getReturnAcceptedQuantity())>0)issues.add("RETURN_PENDING");
            return new Reconciliation(view(h),receipts,manual,returned,accepted,current,issues);
        }));
    }

    private PrdStockReplenishment head(Scope s,String no,boolean lock) {
        var row=originals.selectOne(Wrappers.<PrdStockReplenishment>lambdaQuery()
                .eq(PrdStockReplenishment::getTenantNo,"MAIN").eq(PrdStockReplenishment::getEntityNo,s.entityNo())
                .eq(PrdStockReplenishment::getStoreNo,s.storeNo()).eq(PrdStockReplenishment::getOriginalNo,no)
                .eq(PrdStockReplenishment::getTargetOwner,"PLATFORM").last(lock?"FOR UPDATE":""));
        if(row==null)throw BizException.of(ErrorCode.NOT_FOUND);return row;
    }
    private void save(PrdStockReplenishment h,String actor) {
        h.setUpdatedBy(actor);if(originals.updateById(h)!=1)throw conflict();
    }
    private Mapping mapping(PrdStockReplenishment h){return json.readValue(h.getMappingSnapshot(),Mapping.class);}
    private Target target(PrdStockReplenishment h){return json.readValue(h.getTargetSnapshot(),Target.class);}
    private View view(PrdStockReplenishment h) {
        var original=new StockReplenishmentAdmissionService.Original(h.getOriginalNo(),h.getTargetOwner(),
                json.readValue(h.getSourceSnapshot(),Outbound.class),mapping(h),target(h),h.getExpectedQuantity(),
                h.getReceivedQuantity(),h.getStatus());
        return new View(original,h.getDifferenceQuantity(),h.getReturnedQuantity(),h.getReturnAcceptedQuantity(),h.getCloseReason());
    }
    private static void local(PrdStockWriter writer){if(!"REPLENISH".equals(writer.getMode()))throw conflict();}
    private static int converted(BigDecimal quantity,BigDecimal factor) {
        try{int result=quantity.multiply(factor).intValueExact();if(result<=0)throw conflict();return result;}
        catch(ArithmeticException invalid){throw conflict();}
    }
    private static void audit(BaseEntity row,Scope s,String actor) {
        row.setTenantNo("MAIN");row.setVersion(0L);row.setDeleted(0);row.setCreatedBy(actor);row.setUpdatedBy(actor);
        row.setCreatedAt(LocalDateTime.now());row.setUpdatedAt(row.getCreatedAt());
        if(row instanceof PrdStockReplenishmentManual m){m.setEntityNo(s.entityNo());m.setStoreNo(s.storeNo());}
        else if(row instanceof PrdStockReplenishmentReturn r){r.setEntityNo(s.entityNo());r.setStoreNo(s.storeNo());}
    }
    private static void boundary(Scope s) {
        if(!"MAIN".equals(s.tenantNo()))throw BizException.of(ErrorCode.FORBIDDEN);
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Replenishment query owns its transaction");
    }
    private static BizException conflict(){return BizException.of(ErrorCode.CONFLICT);}
}
