package ai.neargo.shop.food.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.DiningPickupContracts.PickupVerificationVO;
import ai.neargo.shop.food.dto.DiningPickupContracts.VerifyPickupReq;
import ai.neargo.shop.food.entity.FnbCoreOrderBinding;
import ai.neargo.shop.food.entity.FnbOrderBatch;
import ai.neargo.shop.food.entity.FnbOrderLine;
import ai.neargo.shop.food.entity.FnbOrderComponent;
import ai.neargo.shop.food.mapper.FoodMappers.CoreOrderBindingMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OrderBatchMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OrderLineMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OrderComponentMapper;
import ai.neargo.shop.food.service.DiningPickupVerificationService;
import ai.neargo.shop.food.service.KitchenTaskService;
import ai.neargo.shop.spi.trade.IndustryFulfillmentPort;
import ai.neargo.shop.spi.trade.IndustryFulfillmentPort.Reference;
import ai.neargo.shop.spi.trade.IndustryFulfillmentPort.VerifyPickup;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Scope;
import ai.neargo.shop.spi.trade.PricingSourceRef;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.math.BigDecimal;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Local readiness is inspected before a remote original-owner verification; no stock write uses food references. */
public class DiningPickupVerificationServiceImpl implements DiningPickupVerificationService {
    private final OrderBatchMapper batches;
    private final CoreOrderBindingMapper bindings;
    private final KitchenTaskService kitchen;
    private final Supplier<IndustryFulfillmentPort> authority;
    private final OrderLineMapper lines;
    private final OrderComponentMapper components;
    public DiningPickupVerificationServiceImpl(OrderBatchMapper batches,CoreOrderBindingMapper bindings,
            KitchenTaskService kitchen,Supplier<IndustryFulfillmentPort> authority) {
        this(batches,bindings,kitchen,authority,null,null);
    }
    public DiningPickupVerificationServiceImpl(OrderBatchMapper batches,CoreOrderBindingMapper bindings,
            KitchenTaskService kitchen,Supplier<IndustryFulfillmentPort> authority,
            OrderLineMapper lines,OrderComponentMapper components) {
        this.batches=batches; this.bindings=bindings; this.kitchen=kitchen; this.authority=authority;
        this.lines=lines; this.components=components;
    }
    @Override public PickupVerificationVO verify(String entity,String store,String actor,String batch,
            VerifyPickupReq req,String key) {
        context(entity,store,actor,batch,key);
        if (req==null || req.expectedVersion()==null || req.expectedVersion()<0
                || req.expectedVersion()==Long.MAX_VALUE || req.verifyCode()==null
                || !req.verifyCode().matches("[0-9]{6}")) throw bad();
        var reference=reference(entity,store,batch);
        if (!handed(entity,store,batch)) throw BizException.of(ErrorCode.CONFLICT);
        var receipt=owner().verifyPickup(new VerifyPickup(reference,actor,req.expectedVersion(),req.verifyCode()),key);
        if (receipt==null || !reference.equals(receipt.reference())) throw unsupported();
        return new PickupVerificationVO(batch,true,receipt);
    }
    @Override public PickupVerificationVO get(String entity,String store,String actor,String batch,String key) {
        context(entity,store,actor,batch,key); var reference=reference(entity,store,batch);
        var receipt=owner().query(reference,actor,key);
        if (receipt==null || !reference.equals(receipt.reference())) throw unsupported();
        return new PickupVerificationVO(batch,handed(entity,store,batch),receipt);
    }
    private Reference reference(String entity,String store,String number) {
        return DataScopeContext.executeWithoutScope(() -> {
            var batch=batches.selectOne(Wrappers.<FnbOrderBatch>lambdaQuery().eq(FnbOrderBatch::getEntityNo,entity)
                    .eq(FnbOrderBatch::getStoreNo,store).eq(FnbOrderBatch::getBatchNo,number));
            if (batch==null) throw BizException.of(ErrorCode.NOT_FOUND);
            if (!"ACCEPTED".equals(batch.getStatus()) || batch.getAcceptedAt()==null
                    || !"PICKUP".equals(batch.getFulfillment()) || batch.getCartNo()==null
                    || batch.getCartVersion()==null) throw unsupported();
            var binding=bindings.selectOne(Wrappers.<FnbCoreOrderBinding>lambdaQuery()
                    .eq(FnbCoreOrderBinding::getEntityNo,entity).eq(FnbCoreOrderBinding::getBatchNo,number));
            if (binding==null || !"BOUND".equals(binding.getStatus()) || binding.getPaymentRootNo()==null
                    || binding.getStoreOrderNo()==null) throw unsupported();
            return new Reference(new Scope("MAIN",entity,store),new PricingSourceRef("FOOD","CART",batch.getCartNo(),
                    batch.getCartVersion(),null,null),binding.getPaymentRootNo(),binding.getStoreOrderNo());
        });
    }
    private boolean handed(String entity,String store,String batch) {
        // The original owner currently issues entire original items. Net kitchen quantities cannot
        // prove that demand: partial cancellation requires a separate original-owner contract.
        if (lines==null || components==null) return false;
        return DataScopeContext.executeWithoutScope(() -> {
            var tasks=kitchen.queue(entity,store,null,batch).stream().filter(t -> "MAKE".equals(t.ticketKind())).toList();
            if (tasks.isEmpty() || tasks.stream().anyMatch(t -> t.voidQty()==null || t.voidQty().signum()!=0)) return false;
            var original=lines.selectList(Wrappers.<FnbOrderLine>lambdaQuery().eq(FnbOrderLine::getEntityNo,entity)
                    .eq(FnbOrderLine::getBatchNo,batch));
            if (original.isEmpty() || original.stream().map(FnbOrderLine::getFoodLineNo).distinct().count()!=original.size()) return false;
            var expected=new java.util.HashMap<PartKey,BigDecimal>();
            var required=new java.util.HashSet<PartKey>();
            for (var line:original) {
                if (!entity.equals(line.getEntityNo()) || !batch.equals(line.getBatchNo())
                        || line.getFoodLineNo()==null || line.getQty()==null || line.getQty().signum()<=0) return false;
                PartKey parent=partKey(line.getFoodLineNo(),null); expected.put(parent,line.getQty());
                var parts=components.selectList(Wrappers.<FnbOrderComponent>lambdaQuery()
                        .eq(FnbOrderComponent::getEntityNo,entity).eq(FnbOrderComponent::getFoodLineNo,line.getFoodLineNo()));
                if (parts.isEmpty()) required.add(parent);
                else for (var part:parts) {
                    if (!entity.equals(part.getEntityNo()) || !line.getFoodLineNo().equals(part.getFoodLineNo())
                            || part.getComponentNo()==null || part.getQty()==null || part.getQty().signum()<=0) return false;
                    PartKey key=partKey(line.getFoodLineNo(),part.getComponentNo());
                    if (expected.putIfAbsent(key,part.getQty())!=null) return false;
                    required.add(key);
                }
            }
            var covered=new java.util.HashSet<PartKey>();
            for (var task:tasks) {
                PartKey key=partKey(task.foodLineNo(),task.componentNo());
                var full=expected.get(key);
                if (!batch.equals(task.batchNo()) || full==null || task.qty()==null || task.qty().compareTo(full)!=0) return false;
                covered.add(key);
                BigDecimal actual=kitchen.events(entity,store,task.taskNo()).stream()
                        .filter(e -> "HANDOVER".equals(e.action())).map(e -> e.qty())
                        .reduce(BigDecimal.ZERO,BigDecimal::add);
                if (actual.compareTo(full)!=0) return false;
            }
            return covered.containsAll(required);
        });
    }
    private record PartKey(String line,String component) { }
    private static PartKey partKey(String line,String component) { return new PartKey(line,component); }
    private IndustryFulfillmentPort owner() {
        var owner=authority.get(); if (owner==null) throw unsupported(); return owner;
    }
    private static void context(String entity,String store,String actor,String batch,String key) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Original pickup verification must run outside a food transaction");
        }
        for (String value:new String[]{entity,store,actor,batch}) {
            if (value==null || value.isBlank() || value.length()>32 || !value.equals(value.trim())) throw bad();
        }
        if (key==null || key.isBlank() || key.length()>128) throw bad();
    }
    private static BizException bad() { return BizException.of(ErrorCode.BAD_REQUEST); }
    private static BizException unsupported() { return BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED); }
}
