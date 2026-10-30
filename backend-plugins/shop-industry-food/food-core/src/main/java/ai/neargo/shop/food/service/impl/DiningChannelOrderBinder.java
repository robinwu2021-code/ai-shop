package ai.neargo.shop.food.service.impl;

import static ai.neargo.shop.food.service.impl.DiningChannelSourceStore.require;

import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.event.outbox.PublishOptions;
import ai.neargo.shop.food.dto.DiningChannelSourceFacts;
import ai.neargo.shop.food.dto.DiningChannelSourceFacts.Key;
import ai.neargo.shop.food.entity.FnbChannelOrderSource;
import ai.neargo.shop.food.entity.FnbCheckCart;
import ai.neargo.shop.food.entity.FnbCoreLineAllocation;
import ai.neargo.shop.food.entity.FnbCoreOrderBinding;
import ai.neargo.shop.food.entity.FnbOrderBatch;
import ai.neargo.shop.food.entity.FnbOrderComponent;
import ai.neargo.shop.food.entity.FnbOrderLine;
import ai.neargo.shop.food.entity.FnbOrderModifier;
import ai.neargo.shop.food.mapper.FoodMappers.CoreLineAllocationMapper;
import ai.neargo.shop.food.mapper.FoodMappers.CoreOrderBindingMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OrderComponentMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OrderLineMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OrderModifierMapper;
import ai.neargo.shop.spi.food.DiningOrderAccepted;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort.Acceptance;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort.Allocation;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort.Preparation;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort.Status;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;

/**
 * Only a genuine accepted core receipt can atomically become native restaurant lines, quota
 * consumption and dispatch.
 */
public final class DiningChannelOrderBinder {
    private final DiningChannelSourceStore store;
    private final OrderLineMapper lines;
    private final OrderComponentMapper components;
    private final OrderModifierMapper modifiers;
    private final CoreOrderBindingMapper bindings;
    private final CoreLineAllocationMapper allocations;
    private final OutboxPublisher publisher;

    public DiningChannelOrderBinder(
            DiningChannelSourceStore store,
            OrderLineMapper lines,
            OrderComponentMapper components,
            OrderModifierMapper modifiers,
            CoreOrderBindingMapper bindings,
            CoreLineAllocationMapper allocations,
            OutboxPublisher publisher) {
        this.store = store;
        this.lines = lines;
        this.components = components;
        this.modifiers = modifiers;
        this.bindings = bindings;
        this.allocations = allocations;
        this.publisher = publisher;
    }

    void bind(Key key, long fence, Preparation prepared, Acceptance accepted) {
        store.atomic(
                () -> {
                    store.activeGuard(key);
                    var source = store.required(key, true);
                    if ("ACCEPTED".equals(source.getStatus())) {
                        require(
                                Objects.equals(
                                        source.getCoreAcceptanceReceiptRef(),
                                        accepted.acceptanceReceiptRef()));
                        return null;
                    }
                    require(
                            "ACCEPTING".equals(source.getStatus())
                                    && source.getFence() == fence
                                    && source.getLeaseUntil() != null
                                    && source.getLeaseUntil().isAfter(store.now())
                                    && store.reference(source).equals(accepted.reference())
                                    && store.reference(source).equals(prepared.reference())
                                    && prepared.status() == Status.ACCEPTED
                                    && prepared.inventoryPlan() != null
                                    && prepared.orderNo().equals(accepted.orderNo())
                                    && prepared.subOrderNo().equals(accepted.subOrderNo())
                                    && accepted.acceptanceReceiptRef() != null);
                    var data = store.data(source);
                    var frozen = store.frozen(source).withPlan(prepared.inventoryPlan());
                    var batch =
                            store.batches.selectOne(
                                    Wrappers.<FnbOrderBatch>query()
                                            .eq("tenant_no", "MAIN")
                                            .eq("entity_no", key.entityNo())
                                            .eq("store_no", key.storeNo())
                                            .eq("batch_no", source.getBatchNo())
                                            .last("FOR UPDATE"));
                    require(
                            batch != null
                                    && "PREPARED".equals(batch.getStatus())
                                    && "CHANNEL".equals(batch.getPayMode())
                                    && batch.getQuoteRef().equals(prepared.quoteNo())
                                    && batch.getQuoteHash().equals(source.getSourceHash())
                                    && source.getCoreOrderNo().equals(accepted.orderNo())
                                    && source.getCoreSubOrderNo().equals(accepted.subOrderNo()));
                    var mapped = new HashMap<String, Allocation>();
                    for (var allocation : accepted.allocations()) {
                        require(
                                allocation != null
                                        && allocation.orderItemId() > 0
                                        && mapped.putIfAbsent(allocation.componentRef(), allocation)
                                                == null);
                    }
                    require(mapped.size() == data.components().size());
                    require(
                            lines.selectCount(
                                            Wrappers.<FnbOrderLine>query()
                                                    .eq("tenant_no", "MAIN")
                                                    .eq("entity_no", key.entityNo())
                                                    .eq("batch_no", batch.getBatchNo()))
                                    == 0);
                    for (var input : frozen.cartLines()) {
                        var selected =
                                data.components().stream()
                                        .filter(
                                                component ->
                                                        component
                                                                .sourceLineRef()
                                                                .equals(input.sourceLineRef()))
                                        .toList();
                        require(!selected.isEmpty());
                        var main =
                                selected.stream()
                                        .filter(
                                                component ->
                                                        "MAIN".equals(component.externalKind()))
                                        .toList();
                        require(main.size() == 1);
                        var line = new FnbOrderLine();
                        store.stamp(line, key.entityNo());
                        line.setEntityNo(key.entityNo());
                        line.setFoodLineNo(BizKey.next("FLIN"));
                        line.setBatchNo(batch.getBatchNo());
                        line.setGoodsNo(input.goodsNo());
                        line.setSkuNo(input.skuNo());
                        line.setQty(input.qty());
                        line.setSourceLineRef(input.sourceLineRef());
                        line.setUnit(main.getFirst().unit());
                        var definitions =
                                frozen.original().definitions().stream()
                                        .filter(
                                                definition ->
                                                        "MENU".equals(definition.kind())
                                                                && input.menuRevisionNo()
                                                                        .equals(
                                                                                definition
                                                                                        .revisionNo()))
                                        .toList();
                        require(definitions.size() == 1);
                        require(definitions.getFirst().publicationRevision() >= 1);
                        line.setSelectionVersion(definitions.getFirst().publicationRevision());
                        line.setConfigRevisionNos(
                                store.json.writeValueAsString(
                                        frozen.original().definitions().stream()
                                                .map(DiningChannelSourceFacts.Definition::reference)
                                                .toList()));
                        line.setDisplaySnapshot(store.json.writeValueAsString(input));
                        lines.insert(line);
                        var componentNumbers = new HashMap<String, String>();
                        var originalLine =
                                frozen.original().lines().stream()
                                        .filter(
                                                original ->
                                                        original.externalLineRef()
                                                                .equals(
                                                                        main.getFirst()
                                                                                .externalLineRef()))
                                        .toList();
                        require(originalLine.size() == 1);
                        for (var component : selected) {
                            var allocation = mapped.remove(component.componentRef());
                            require(
                                    allocation != null
                                            && input.sourceLineRef()
                                                    .equals(allocation.sourceLineRef())
                                            && component
                                                    .externalLineRef()
                                                    .equals(allocation.externalLineRef())
                                            && Objects.equals(
                                                    component.parentExternalLineRef(),
                                                    allocation.parentExternalLineRef())
                                            && component
                                                    .externalKind()
                                                    .equals(allocation.externalKind())
                                            && component
                                                            .physicalQuantity()
                                                            .compareTo(
                                                                    new BigDecimal(
                                                                            allocation
                                                                                    .physicalQuantity()))
                                                    == 0
                                            && component.unit().equals(allocation.unit())
                                            && component.netMinor() == allocation.amountMinor()
                                            && data.currency().equals(allocation.currency()));
                            var nativeComponent = new FnbOrderComponent();
                            store.stamp(nativeComponent, key.entityNo());
                            nativeComponent.setEntityNo(key.entityNo());
                            nativeComponent.setComponentNo(BizKey.next("FCMP"));
                            nativeComponent.setFoodLineNo(line.getFoodLineNo());
                            nativeComponent.setSkuNo(component.skuNo());
                            nativeComponent.setComponentKind(
                                    "BUNDLE_CONTAINER".equals(component.productionKind())
                                            ? "MAIN"
                                            : component.productionKind());
                            var drafts =
                                    originalLine.getFirst().components().stream()
                                            .filter(
                                                    draft ->
                                                            PricingSnapshotHash.of(
                                                                            List.of(
                                                                                    source
                                                                                            .getSourceNo(),
                                                                                    input
                                                                                            .sourceLineRef(),
                                                                                    draft
                                                                                            .logicalKey()))
                                                                    .equals(
                                                                            component
                                                                                    .componentRef()))
                                            .toList();
                            require(drafts.size() == 1);
                            nativeComponent.setChoiceNo(drafts.getFirst().bundleChoiceNo());
                            nativeComponent.setSourceComponentRef(component.componentRef());
                            nativeComponent.setQty(component.physicalQuantity());
                            nativeComponent.setSelectionSnapshot(
                                    store.json.writeValueAsString(component));
                            components.insert(nativeComponent);
                            componentNumbers.put(
                                    component.externalLineRef(), nativeComponent.getComponentNo());
                            var link = new FnbCoreLineAllocation();
                            store.stamp(link, key.entityNo());
                            link.setEntityNo(key.entityNo());
                            link.setAllocationNo(BizKey.next("FCLA"));
                            link.setFoodLineNo(line.getFoodLineNo());
                            link.setSourceComponentRef(component.componentRef());
                            link.setOrderItemId(allocation.orderItemId());
                            link.setQty(component.physicalQuantity());
                            link.setCoreSnapshotHash(allocation.receiptRef());
                            link.setCoreAmountMinor(allocation.amountMinor());
                            link.setCurrency(allocation.currency());
                            link.setUnit(allocation.unit());
                            allocations.insert(link);
                        }
                        for (var choice : input.selections()) {
                            var modifier = new FnbOrderModifier();
                            store.stamp(modifier, key.entityNo());
                            modifier.setEntityNo(key.entityNo());
                            modifier.setSelectionNo(BizKey.next("FSEL"));
                            modifier.setFoodLineNo(line.getFoodLineNo());
                            modifier.setSourceKind(choice.sourceKind());
                            modifier.setSourceNo(choice.sourceNo());
                            modifier.setQty(choice.qty());
                            modifier.setSelectionSnapshot(store.json.writeValueAsString(choice));
                            // Non-SKU tastes are retained without manufacturing a financial
                            // component or stock item.
                            if ("MODIFIER".equals(choice.sourceKind())) {
                                var possible =
                                        originalLine.getFirst().components().stream()
                                                .filter(
                                                        component ->
                                                                "OPTION"
                                                                                .equals(
                                                                                        component
                                                                                                .externalKind())
                                                                        && choice.groupNo()
                                                                                .equals(
                                                                                        component
                                                                                                .selectionGroupNo())
                                                                        && choice.sourceKind()
                                                                                .equals(
                                                                                        component
                                                                                                .selectionSourceKind())
                                                                        && choice.sourceNo()
                                                                                .equals(
                                                                                        component
                                                                                                .selectionSourceNo()))
                                                .toList();
                                require(possible.size() <= 1);
                                if (!possible.isEmpty()) {
                                    String number =
                                            componentNumbers.get(
                                                    possible.getFirst().externalLineRef());
                                    require(number != null);
                                    modifier.setComponentNo(number);
                                }
                            }
                            modifiers.insert(modifier);
                        }
                    }
                    require(mapped.isEmpty());
                    var binding = new FnbCoreOrderBinding();
                    store.stamp(binding, key.entityNo());
                    binding.setEntityNo(key.entityNo());
                    binding.setBindingNo(BizKey.next("FCOB"));
                    binding.setBatchNo(batch.getBatchNo());
                    binding.setStatus("BOUND");
                    binding.setPaymentRootNo(accepted.orderNo());
                    binding.setStoreOrderNo(accepted.subOrderNo());
                    bindings.insert(binding);
                    store.quotas.consume(key.entityNo(), key.storeNo(), batch.getBatchNo());
                    require(
                            store.batches.update(
                                            Wrappers.<FnbOrderBatch>update()
                                                    .eq("tenant_no", "MAIN")
                                                    .eq("id", batch.getId())
                                                    .eq("version", batch.getVersion())
                                                    .set("status", "ACCEPTED")
                                                    .set("accepted_at", store.now())
                                                    .set(
                                                            "frozen_request",
                                                            store.json.writeValueAsString(frozen))
                                                    .setSql("version=version+1"))
                                    == 1);
                    if (batch.getScheduledAt() != null) {
                        require(store.slots != null);
                        store.slots.bindOrder(
                                key.entityNo(),
                                key.storeNo(),
                                "SYSTEM",
                                batch.getCartNo(),
                                batch.getBatchNo());
                    }
                    require(
                            store.carts.update(
                                            Wrappers.<FnbCheckCart>update()
                                                    .eq("tenant_no", "MAIN")
                                                    .eq("entity_no", key.entityNo())
                                                    .eq("store_no", key.storeNo())
                                                    .eq("cart_no", source.getCartNo())
                                                    .eq("status", "LOCKED")
                                                    .set("status", "CLOSED")
                                                    .setSql("version=version+1"))
                                    == 1);
                    String receipt = BizKey.next("FCAC");
                    require(
                            store.sources.update(
                                            Wrappers.<FnbChannelOrderSource>update()
                                                    .eq("tenant_no", "MAIN")
                                                    .eq("id", source.getId())
                                                    .eq("version", source.getVersion())
                                                    .set("status", "ACCEPTED")
                                                    .set("acceptance_receipt_ref", receipt)
                                                    .set(
                                                            "core_acceptance_receipt_ref",
                                                            accepted.acceptanceReceiptRef())
                                                    .set("lease_until", null)
                                                    .set(
                                                            "core_preparation_snapshot",
                                                            store.json.writeValueAsString(prepared))
                                                    .set(
                                                            "core_acceptance_snapshot",
                                                            store.json.writeValueAsString(accepted))
                                                    .set("next_recovery_at", null)
                                                    .setSql("version=version+1"))
                                    == 1);
                    publisher.publish(
                            new DiningOrderAccepted(
                                    batch.getBatchNo(),
                                    key.entityNo(),
                                    key.storeNo(),
                                    null,
                                    accepted.orderNo(),
                                    accepted.subOrderNo(),
                                    batch.getBusinessDate(),
                                    batch.getFulfillment(),
                                    store.clock.instant()),
                            new PublishOptions("FOOD:BATCH:" + batch.getBatchNo(), 1));
                    return null;
                });
    }
}
