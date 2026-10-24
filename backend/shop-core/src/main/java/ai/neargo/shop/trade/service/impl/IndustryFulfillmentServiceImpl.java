package ai.neargo.shop.trade.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.Fulfillments;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.shop.spi.inventory.InventoryReservationPort;
import ai.neargo.shop.spi.inventory.InventoryReservationPort.Snapshot;
import ai.neargo.shop.spi.inventory.InventoryReservationPort.State;
import ai.neargo.shop.spi.product.StockPort;
import ai.neargo.shop.spi.trade.IndustryFulfillmentPort.Receipt;
import ai.neargo.shop.spi.trade.IndustryFulfillmentPort.Reference;
import ai.neargo.shop.spi.trade.IndustryFulfillmentPort.VerifyPickup;
import ai.neargo.shop.spi.trade.OrderEvents;
import ai.neargo.shop.spi.trade.PricingSourceFacts;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Scope;
import ai.neargo.shop.spi.trade.PricingSourceRef;
import ai.neargo.shop.spi.user.MerchantScopeFactsPort;
import ai.neargo.shop.trade.entity.OrdIndustryFulfillment;
import ai.neargo.shop.trade.entity.OrdIndustryOrderSource;
import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.mapper.IndustryFulfillmentMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.IndustryOrderSourceMapper;
import ai.neargo.shop.trade.order.entity.OrdCreateIntent;
import ai.neargo.shop.trade.order.machine.order.OrderContext;
import ai.neargo.shop.trade.order.machine.order.OrderEvent;
import ai.neargo.shop.trade.order.mapper.CreateIntentMapper;
import ai.neargo.shop.trade.order.mapper.OrderItemMapper;
import ai.neargo.shop.trade.order.mapper.PaymentMapper;
import ai.neargo.shop.trade.order.mapper.StatusCasMapper;
import ai.neargo.shop.trade.order.mapper.StoreOrderMapper;
import ai.neargo.shop.trade.order.statemachine.Actor;
import ai.neargo.shop.trade.order.transition.TradeStatusWriter;
import ai.neargo.shop.trade.service.IndustryFulfillmentService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Durable source-authorized pickup verification. External inventory issue never holds a platform order lock. */
@Service
public class IndustryFulfillmentServiceImpl implements IndustryFulfillmentService {
    private final IndustryFulfillmentMapper journal;
    private final IndustryOrderSourceMapper sources;
    private final PaymentMapper orders;
    private final StoreOrderMapper subs;
    private final OrderItemMapper items;
    private final CreateIntentMapper intents;
    private final StatusCasMapper locks;
    private final MerchantScopeFactsPort owners;
    private final ObjectProvider<InventoryReservationPort> inventory;
    private final TradeStatusWriter writer;
    private final OutboxEventBus events;
    private final ObjectMapper json;
    private final TransactionTemplate write;
    private record Facts(OrdIndustryOrderSource source,OrdOrder order,OrdSubOrder sub,List<OrdItem> items,
                         OrdCreateIntent intent,boolean openAfterSale) { }

    public IndustryFulfillmentServiceImpl(IndustryFulfillmentMapper journal,IndustryOrderSourceMapper sources,
            PaymentMapper orders,StoreOrderMapper subs,OrderItemMapper items,CreateIntentMapper intents,
            MerchantScopeFactsPort owners,ObjectProvider<InventoryReservationPort> inventory,TradeStatusWriter writer,
            OutboxEventBus events,ObjectMapper json,@Qualifier("transactionManager") PlatformTransactionManager manager,
            StatusCasMapper locks) {
        this.journal=journal; this.sources=sources; this.orders=orders; this.subs=subs; this.items=items;
        this.intents=intents; this.locks=locks; this.owners=owners; this.inventory=inventory; this.writer=writer; this.events=events;
        this.json=json; write=new TransactionTemplate(manager);
        write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    @Override public Receipt verifyPickup(VerifyPickup command,String key) {
        outside();
        if (command==null || command.expectedVersion()<0 || command.expectedVersion()==Long.MAX_VALUE
                || command.verifyCode()==null || !command.verifyCode().matches("[0-9]{6}")) throw bad();
        authorize(command.reference(),command.actorNo(),key);
        var ref=command.reference();
        String operation=operation(ref,key);
        String request=PricingSnapshotHash.of(List.of("INDUSTRY_PICKUP_V1",ref,command.actorNo(),
                command.expectedVersion(),codeHash(command.verifyCode())));
        OrdIndustryFulfillment prepared;
        try {
            prepared=tx(() -> {
                var facts=facts(ref,true);
                var prior=byKey(ref,operation,true);
                if (prior!=null) { same(prior,ref,command.actorNo(),request); return prior; }
                eligible(facts,false);
                if (!command.verifyCode().equals(facts.sub().getVerifyCode())
                        || command.expectedVersion()!=facts.sub().getVersion()) throw conflict();
                var row=new OrdIndustryFulfillment(); stamp(row,command.actorNo());
                row.setFulfillmentNo(BizKey.next("IFUL")); row.setEntityNo(ref.scope().entityNo());
                row.setStoreNo(ref.scope().storeNo()); row.setOrderNo(ref.orderNo()); row.setSubOrderNo(ref.subOrderNo());
                row.setSourceSystem(ref.source().sourceSystem()); row.setSourceType(ref.source().sourceType());
                row.setSourceNo(ref.source().sourceNo()); row.setSourceVersion(ref.source().sourceVersion());
                row.setSourceHash(facts.source().getSourceHash()); row.setActorNo(command.actorNo());
                row.setOperationKey(operation); row.setRequestHash(request); row.setVerifyCodeHash(codeHash(command.verifyCode()));
                row.setExpectedSubVersion(command.expectedVersion()); row.setStatus("PREPARED"); journal.insert(row);
                return row;
            });
        } catch (DuplicateKeyException concurrent) {
            prepared=sql(() -> byKey(ref,operation,false));
            if (prepared==null) throw conflict(); same(prepared,ref,command.actorNo(),request);
        }
        if (terminal(prepared)) return project(prepared,sql(() -> facts(ref,false)));
        try {
            var before=sql(() -> facts(ref,false)); eligible(before,false); original(prepared,before);
            if (!prepared.getExpectedSubVersion().equals(before.sub().getVersion())) throw conflict();
            var scope=inventoryScope(ref);
            var known=provider().query(scope); inventoryProof(before,known,false);
            // An uncertain earlier issue is queried first; an already issued original child is not reissued.
            Snapshot issued=issued(known)?known:provider().issue(scope,ref.subOrderNo());
            inventoryProof(before,issued,true);
            return finish(prepared,ref,issued);
        } catch (RuntimeException uncertain) {
            markUnknown(prepared,"PICKUP_RECONCILIATION_REQUIRED");
            if (uncertain instanceof BizException rejected) throw rejected;
            return project(sql(() -> byKey(ref,operation,false)),sql(() -> facts(ref,false)));
        }
    }
    @Override public Receipt query(Reference ref,String actor,String key) {
        outside(); authorize(ref,actor,key);
        var facts=sql(() -> facts(ref,false));
        var row=sql(() -> byKey(ref,operation(ref,key),false));
        if (row==null) {
            eligible(facts,false);
            return new Receipt(null,ref,facts.sub().getVerifyCode()==null?"NOT_READY_CODE":"NOT_STARTED",
                    facts.sub().getStatus(),buyerConfirm(facts.sub()),true,null,null,
                    facts.sub().getInventoryLifecycleVersion(),facts.sub().getVersion());
        }
        sameReference(row,ref); if (!actor.equals(row.getActorNo())) throw forbidden();
        if (terminal(row)) return project(row,facts);
        try {
            original(row,facts);
            var known=provider().query(inventoryScope(ref)); inventoryProof(facts,known,false);
            if (issued(known)) return finish(row,ref,known);
        } catch (RuntimeException uncertain) { markUnknown(row,"PICKUP_QUERY_UNCERTAIN"); }
        return project(sql(() -> byKey(ref,operation(ref,key),false)),sql(() -> facts(ref,false)));
    }
    private Receipt finish(OrdIndustryFulfillment original,Reference ref,Snapshot proof) {
        return tx(() -> {
            var facts=facts(ref,true); var row=byKey(ref,original.getOperationKey(),true);
            if (row==null) throw conflict(); if (terminal(row)) return project(row,facts);
            eligible(facts,true); original(row,facts); inventoryProof(facts,proof,true);
            if (!OrdSubOrder.COMPLETED.equals(facts.sub().getStatus())) {
                if (!row.getExpectedSubVersion().equals(facts.sub().getVersion())) throw conflict();
                writer.order(facts.sub(),OrderEvent.VERIFIED,Actor.MERCHANT,row.getActorNo(),"门店自提核销",
                        OrderContext.authorizedOnly().withRequireBuyerConfirm(buyerConfirm(facts.sub())));
                if (OrdSubOrder.COMPLETED.equals(facts.sub().getStatus())) {
                    events.publish(new OrderEvents.SubOrderCompleted(ref.subOrderNo(),ref.orderNo(),
                            ref.scope().entityNo(),facts.sub().getUserNo(),facts.sub().getFulfillment()));
                }
            }
            row.setInventoryReceiptJson(json.writeValueAsString(proof));
            row.setStatus(buyerConfirm(facts.sub()) && !OrdSubOrder.COMPLETED.equals(facts.sub().getStatus())
                    ?"BUYER_CONFIRM_PENDING":"VERIFIED");
            row.setUpdatedAt(LocalDateTime.now()); if (journal.updateById(row)!=1) throw conflict();
            return project(row,facts);
        });
    }
    private Facts facts(Reference ref,boolean lock) {
        var scope=ref.scope();
        var source=sources.selectOne(Wrappers.<OrdIndustryOrderSource>lambdaQuery()
                .eq(OrdIndustryOrderSource::getTenantNo,scope.tenantNo())
                .eq(OrdIndustryOrderSource::getEntityNo,scope.entityNo())
                .eq(OrdIndustryOrderSource::getStoreNo,scope.storeNo()).eq(OrdIndustryOrderSource::getOrderNo,ref.orderNo())
                .eq(OrdIndustryOrderSource::getSubOrderNo,ref.subOrderNo()));
        if (source==null) throw missing();
        if (!scope.tenantNo().equals(source.getTenantNo()) || !scope.entityNo().equals(source.getEntityNo())
                || !scope.storeNo().equals(source.getStoreNo()) || !ref.orderNo().equals(source.getOrderNo())
                || !ref.subOrderNo().equals(source.getSubOrderNo())) throw forbidden();
        if (!ref.source().sourceSystem().equals(source.getSourceSystem())
                || !ref.source().sourceType().equals(source.getSourceType())
                || !ref.source().sourceNo().equals(source.getSourceNo())
                || ref.source().sourceVersion()!=source.getSourceVersion()) throw forbidden();
        var order=orders.selectOne(Wrappers.<OrdOrder>lambdaQuery().eq(OrdOrder::getTenantNo,scope.tenantNo())
                .eq(OrdOrder::getOrderNo,ref.orderNo()).last(lock?"FOR UPDATE":""));
        var sub=subs.selectOne(Wrappers.<OrdSubOrder>lambdaQuery().eq(OrdSubOrder::getTenantNo,scope.tenantNo())
                .eq(OrdSubOrder::getEntityNo,scope.entityNo()).eq(OrdSubOrder::getStoreNo,scope.storeNo())
                .eq(OrdSubOrder::getOrderNo,ref.orderNo()).eq(OrdSubOrder::getSubOrderNo,ref.subOrderNo())
                .last(lock?"FOR UPDATE":""));
        if (order==null || sub==null || !ref.orderNo().equals(order.getOrderNo())
                || !ref.orderNo().equals(sub.getOrderNo()) || !ref.subOrderNo().equals(sub.getSubOrderNo())
                || !scope.entityNo().equals(sub.getEntityNo()) || !scope.storeNo().equals(sub.getStoreNo())
                || !source.getBuyerNo().equals(order.getUserNo())
                || !source.getBuyerNo().equals(sub.getUserNo())) throw missing();
        var all=items.selectList(Wrappers.<OrdItem>lambdaQuery().eq(OrdItem::getTenantNo,scope.tenantNo())
                .eq(OrdItem::getOrderNo,ref.orderNo()).eq(OrdItem::getSubOrderNo,ref.subOrderNo()));
        var intent=intents.selectOne(Wrappers.<OrdCreateIntent>lambdaQuery().eq(OrdCreateIntent::getTenantNo,scope.tenantNo())
                .eq(OrdCreateIntent::getPayNo,ref.orderNo()));
        return new Facts(source,order,sub,all,intent,locks.openAfterSaleNo(ref.subOrderNo())!=null);
    }
    private void eligible(Facts facts,boolean allowCompleted) {
        var source=facts.source(); var sub=facts.sub();
        if (!Fulfillments.STORE_PICKUP.equals(sub.getFulfillment())
                || sub.getInventoryLifecycleVersion()==null || !Set.of(2,3).contains(sub.getInventoryLifecycleVersion())
                || facts.intent()==null || !facts.order().getOrderNo().equals(facts.intent().getPayNo())
                || !facts.source().getBuyerNo().equals(facts.intent().getUserNo())
                || !OrdCreateIntent.COMMITTED.equals(facts.intent().getStatus())
                || !"INDUSTRY_INVENTORY_V2".equals(facts.intent().getStockAuthority())) throw unsupported();
        if (facts.openAfterSale() || facts.items().isEmpty()) throw conflict();
        if (!Set.of(OrdSubOrder.WAIT_FULFILL,OrdSubOrder.FULFILLING).contains(sub.getStatus())
                && !(allowCompleted && OrdSubOrder.COMPLETED.equals(sub.getStatus()))) throw conflict();
        if (sub.getInventoryLifecycleVersion()==2) {
            if (!"PREPAY".equals(source.getOrderMode()) || !Set.of("BOUND","ACCEPTED").contains(source.getStatus())
                    || !OrdOrder.PAID.equals(facts.order().getStatus()) || facts.order().getPaidAt()==null) throw conflict();
        } else if (!"POSTPAY".equals(source.getOrderMode()) || !"ACCEPTED".equals(source.getStatus())
                || !(OrdOrder.WAIT_PAY.equals(facts.order().getStatus()) || OrdOrder.PAID.equals(facts.order().getStatus()))) {
            throw conflict();
        }
        for (var item:facts.items()) if (item.getRefundedQty()!=null && item.getRefundedQty()>0) throw conflict();
    }
    private void original(OrdIndustryFulfillment row,Facts facts) {
        if (!row.getSourceHash().equals(facts.source().getSourceHash())
                || !row.getVerifyCodeHash().equals(codeHash(facts.sub().getVerifyCode()))) throw conflict();
    }
    private void inventoryProof(Facts facts,Snapshot proof,boolean requireIssued) {
        var scope=new InventoryReservationPort.Scope(facts.source().getTenantNo(),facts.source().getEntityNo(),
                facts.source().getOrderNo());
        if (proof==null || !scope.equals(proof.scope()) || proof.reservationId()==null
                || proof.lifecycleVersion()!=facts.sub().getInventoryLifecycleVersion()) throw conflict();
        if (proof.lifecycleVersion()==2 && (!proof.paymentAcknowledged()
                || !Set.of(State.ALLOCATED,State.COMMITTED).contains(proof.state()))) throw conflict();
        if (proof.lifecycleVersion()==3) {
            boolean paid=OrdOrder.PAID.equals(facts.order().getStatus()) && facts.order().getPaidAt()!=null;
            if (proof.paymentAcknowledged()!=paid || !(paid?Set.of(State.ALLOCATED,State.COMMITTED):
                    Set.of(State.ACCEPTED_UNPAID,State.ISSUED_UNPAID)).contains(proof.state())) throw conflict();
        }
        if (requireIssued && !issued(proof)) throw conflict();
        var expected=new HashMap<String,Integer>();
        for (var item:facts.items()) {
            if (item.getQty()==null || item.getQty()<=0 || item.getSkuNo()==null) throw conflict();
            expected.merge(item.getSkuNo(),item.getQty(),Math::addExact);
        }
        var actual=new HashMap<String,Integer>();
        for (var line:proof.originalLines()) {
            if (!facts.sub().getSubOrderNo().equals(line.fulfillmentRef())) throw conflict();
            actual.merge(line.skuNo(),line.qty(),Math::addExact);
        }
        if (!expected.equals(actual)) throw conflict();
        List<StockPort.SkuQty> original=json.readValue(facts.intent().getStockNeedsJson(),new TypeReference<>() { });
        var retained=new HashMap<String,Integer>();
        for (var line:original) {
            if (!facts.sub().getStoreNo().equals(line.storeNo())
                    || !facts.sub().getSubOrderNo().equals(line.fulfillmentRef())
                    || line.lifecycleVersion()!=proof.lifecycleVersion()) throw conflict();
            retained.merge(line.skuNo(),line.qty(),Math::addExact);
        }
        if (!expected.equals(retained)) throw conflict();
    }
    private Receipt project(OrdIndustryFulfillment row,Facts facts) {
        Snapshot snapshot=row.getInventoryReceiptJson()==null?null:json.readValue(row.getInventoryReceiptJson(),Snapshot.class);
        String status=row.getStatus();
        if ("BUYER_CONFIRM_PENDING".equals(status) && OrdSubOrder.COMPLETED.equals(facts.sub().getStatus())) status="VERIFIED";
        return new Receipt(row.getFulfillmentNo(),reference(row),status,facts.sub().getStatus(),buyerConfirm(facts.sub()),
                !terminal(row),snapshot==null?null:snapshot.reservationId(),snapshot==null?null:snapshot.state().name(),
                facts.sub().getInventoryLifecycleVersion(),facts.sub().getVersion());
    }
    private OrdIndustryFulfillment byKey(Reference ref,String operation,boolean lock) {
        return journal.selectOne(Wrappers.<OrdIndustryFulfillment>lambdaQuery()
                .eq(OrdIndustryFulfillment::getTenantNo,ref.scope().tenantNo())
                .eq(OrdIndustryFulfillment::getEntityNo,ref.scope().entityNo())
                .eq(OrdIndustryFulfillment::getStoreNo,ref.scope().storeNo())
                .eq(OrdIndustryFulfillment::getOperationKey,operation).last(lock?"FOR UPDATE":""));
    }
    private void markUnknown(OrdIndustryFulfillment row,String code) {
        tx(() -> {
            var current=byKey(reference(row),row.getOperationKey(),true);
            if (current!=null && !terminal(current)) {
                current.setStatus("UNKNOWN"); current.setErrorCode(code); current.setUpdatedAt(LocalDateTime.now());
                if (journal.updateById(current)!=1) throw conflict();
            }
            return null;
        });
    }
    private void authorize(Reference ref,String actor,String key) {
        if (ref==null || ref.scope()==null || ref.source()==null || !"MAIN".equals(ref.scope().tenantNo())
                || !"FOOD".equals(ref.source().sourceSystem()) || !"CART".equals(ref.source().sourceType())
                || ref.source().scopeNo()!=null || key==null || key.isBlank() || key.length()>128) throw bad();
        try {
            PricingSourceFacts.key(actor); PricingSourceFacts.key(ref.orderNo()); PricingSourceFacts.key(ref.subOrderNo());
            PricingSourceFacts.key(ref.scope().entityNo()); PricingSourceFacts.key(ref.scope().storeNo());
        } catch (IllegalArgumentException malformed) { throw bad(); }
        if (!owners.entityActive("MAIN",ref.scope().entityNo())
                || !owners.storesOwned("MAIN",ref.scope().entityNo(),Set.of(ref.scope().storeNo()))) throw forbidden();
    }
    private InventoryReservationPort provider() {
        var providers=inventory.orderedStream().limit(2).toList(); if (providers.size()!=1) throw unsupported();
        return providers.getFirst();
    }
    private <T> T tx(Supplier<T> action) { return sql(() -> write.execute(tx -> action.get())); }
    private static <T> T sql(Supplier<T> action) { return DataScopeContext.executeWithoutScope(action); }
    private static void same(OrdIndustryFulfillment row,Reference ref,String actor,String request) {
        sameReference(row,ref);
        if (!actor.equals(row.getActorNo()) || !request.equals(row.getRequestHash())) throw conflict();
    }
    private static void sameReference(OrdIndustryFulfillment row,Reference ref) {
        if (!reference(row).equals(ref)) throw conflict();
    }
    private static Reference reference(OrdIndustryFulfillment row) {
        return new Reference(new Scope(row.getTenantNo(),
                row.getEntityNo(),row.getStoreNo()),new PricingSourceRef(row.getSourceSystem(),
                row.getSourceType(),row.getSourceNo(),row.getSourceVersion(),null,null),row.getOrderNo(),row.getSubOrderNo());
    }
    private static InventoryReservationPort.Scope inventoryScope(Reference ref) {
        return new InventoryReservationPort.Scope(ref.scope().tenantNo(),ref.scope().entityNo(),ref.orderNo());
    }
    private static String operation(Reference ref,String key) {
        return PricingSnapshotHash.of(List.of("INDUSTRY_FULFILLMENT_KEY_V1",ref.scope(),key));
    }
    private static String codeHash(String code) {
        if (code==null) throw conflict(); return PricingSnapshotHash.of(List.of("PICKUP_CODE_V1",code));
    }
    private static boolean issued(Snapshot proof) {
        return proof!=null && (proof.state()==State.COMMITTED || proof.state()==State.ISSUED_UNPAID);
    }
    private static boolean terminal(OrdIndustryFulfillment row) {
        return Set.of("VERIFIED","BUYER_CONFIRM_PENDING").contains(row.getStatus());
    }
    private static boolean buyerConfirm(OrdSubOrder sub) { return Integer.valueOf(1).equals(sub.getRequireBuyerConfirm()); }
    private static void stamp(OrdIndustryFulfillment row,String actor) {
        row.setTenantNo("MAIN"); row.setCreatedBy(actor); row.setUpdatedBy(actor); row.setVersion(0L); row.setDeleted(0);
        row.setCreatedAt(LocalDateTime.now()); row.setUpdatedAt(LocalDateTime.now());
    }
    private static void outside() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Original pickup workflow must run outside a platform transaction");
        }
    }
    private static BizException bad() { return BizException.of(ErrorCode.BAD_REQUEST); }
    private static BizException missing() { return BizException.of(ErrorCode.NOT_FOUND); }
    private static BizException forbidden() { return BizException.of(ErrorCode.FORBIDDEN); }
    private static BizException conflict() { return BizException.of(ErrorCode.CONFLICT); }
    private static BizException unsupported() { return BizException.of(ErrorCode.PAY_MODE_NOT_SUPPORTED); }
}
