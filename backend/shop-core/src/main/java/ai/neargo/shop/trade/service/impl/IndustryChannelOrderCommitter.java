package ai.neargo.shop.trade.service.impl;

import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort.Plan;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort.Receipt;
import ai.neargo.shop.spi.product.GoodsQueryPort.SkuSnapshot;
import ai.neargo.shop.spi.trade.IndustryChannelOrderFacts;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort.Acceptance;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort.Allocation;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort.Reference;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort.CollectionFacts;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort.Data;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort.PlatformAcceptance;
import ai.neargo.shop.spi.trade.IndustryOrderPort;
import ai.neargo.shop.spi.trade.OrderEvents;
import ai.neargo.shop.trade.entity.OrdChannelImport;
import ai.neargo.shop.trade.entity.OrdChannelQuote;
import ai.neargo.shop.trade.entity.OrdIndustryOrderSource;
import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.entity.OrdStatusLog;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.mapper.TradeMappers.IndustryOrderSourceMapper;
import ai.neargo.shop.trade.order.mapper.OrderItemMapper;
import ai.neargo.shop.trade.order.mapper.OrderStatusLogMapper;
import ai.neargo.shop.trade.order.mapper.PaymentMapper;
import ai.neargo.shop.trade.order.mapper.StoreOrderMapper;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Atomic original order creation after independent inventory authorization, without a local payment
 * transition.
 */
@Component
public final class IndustryChannelOrderCommitter {
    private final IndustryChannelOrderStore store;
    private final PaymentMapper orders;
    private final StoreOrderMapper subOrders;
    private final OrderItemMapper items;
    private final IndustryOrderSourceMapper sources;
    private final OrderStatusLogMapper logs;
    private final OutboxEventBus events;

    public IndustryChannelOrderCommitter(
            IndustryChannelOrderStore store,
            PaymentMapper orders,
            StoreOrderMapper subOrders,
            OrderItemMapper items,
            IndustryOrderSourceMapper sources,
            OrderStatusLogMapper logs,
            OutboxEventBus events) {
        this.store = store;
        this.orders = orders;
        this.subOrders = subOrders;
        this.items = items;
        this.sources = sources;
        this.logs = logs;
        this.events = events;
    }

    record BoundAllocations(
            List<IndustryOrderPort.Allocation> allocations, List<Object> evidence) {}

    record ConfirmationEvidence(
            PlatformAcceptance acceptance, CollectionFacts collection, Receipt inventory) {}

    record StoredAcceptance(List<Allocation> allocations) {}

    Acceptance commit(
            Reference ref,
            long fence,
            Plan plan,
            Receipt inventory,
            PlatformAcceptance acceptance,
            CollectionFacts collection,
            Map<String, SkuSnapshot> skus,
            String merchantName) {
        return store.atomic(
                () -> {
                    store.activeGuard(ref);
                    var row = store.required(ref, true);
                    if ("ACCEPTED".equals(row.getStatus())) return accepted(row, ref);
                    require(
                            row.getFence() == fence
                                    && "CONFIRMING".equals(row.getStatus())
                                    && row.getLeaseUntil() != null
                                    && row.getLeaseUntil().isAfter(store.now()));
                    var data = store.data(row);
                    IndustryChannelOrderFacts.acceptance(ref, data, acceptance, store.clock);
                    IndustryChannelOrderFacts.collection(ref, data, collection, store.clock);
                    IndustryChannelStockPreparation.validateReceipt(plan, inventory);
                    require("ACCEPTED_UNPAID".equals(inventory.status()));
                    for (var line : inventory.lines())
                        require(line.issuedQty().signum() == 0 && line.releasedQty().signum() == 0);
                    var preparation = store.preparation(row, true);
                    require(
                            plan.planHash().equals(preparation.getPlanHash())
                                    && store.json
                                            .readValue(preparation.getPlanSnapshot(), Plan.class)
                                            .equals(plan));
                    String receipt = BizKey.next("OCA");
                    var totals = data.totals();
                    var order = new OrdOrder();
                    store.audit(order, ref.scope());
                    order.setOrderNo(row.getOrderNo());
                    order.setCustomerKind("CHANNEL");
                    order.setAdmissionKind("CHANNEL");
                    order.setCaller(ref.sourceSystem());
                    order.setEntityNo(row.getEntityNo());
                    order.setStoreNo(row.getStoreNo());
                    order.setOperationNo(row.getAdmissionNo());
                    order.setCurrency(data.currency());
                    order.setGoodsAmount(
                            Math.addExact(totals.grossExTaxMinor(), totals.taxMinor()));
                    order.setDiscountAmount(totals.merchantDiscountExTaxMinor());
                    order.setFreightAmount(0L);
                    order.setFeeMinor(totals.merchantChargesMinor());
                    order.setPayAmount(totals.merchantReceivableMinor());
                    order.setStatus(OrdOrder.WAIT_CHANNEL_PAY);
                    orders.insert(order);
                    var sub = new OrdSubOrder();
                    store.audit(sub, ref.scope());
                    sub.setOrderNo(row.getOrderNo());
                    sub.setSubOrderNo(row.getSubOrderNo());
                    sub.setCustomerKind("CHANNEL");
                    sub.setAdmissionKind("CHANNEL");
                    sub.setCaller(ref.sourceSystem());
                    sub.setEntityNo(row.getEntityNo());
                    sub.setStoreNo(row.getStoreNo());
                    sub.setEntityName(merchantName);
                    sub.setFulfillment(fulfillment(data));
                    sub.setInventoryLifecycleVersion(3);
                    sub.setTrafficSource(OrdSubOrder.TRAFFIC_MERCHANT_OWNED);
                    sub.setGoodsAmount(order.getGoodsAmount());
                    sub.setDiscountAmount(order.getDiscountAmount());
                    sub.setDiscountMerchant(order.getDiscountAmount());
                    sub.setDiscountPlatform(0L);
                    sub.setFreightAmount(0L);
                    sub.setFeeMinor(order.getFeeMinor());
                    sub.setPayAmount(order.getPayAmount());
                    sub.setStatus(OrdSubOrder.WAIT_FULFILL);
                    sub.setBuyerNickname(data.party().displayName());
                    if ("STORE_PICKUP".equals(sub.getFulfillment()))
                        sub.setVerifyCode(
                                OrderServiceImpl.originalOwnerUnusedVerifyCode(subOrders));
                    sub.setRefundedMinor(0L);
                    sub.setRefundProgress("NONE");
                    sub.setRequireBuyerConfirm(0);
                    if (data.delivery() != null) {
                        sub.setReceiverName(data.delivery().receiverName());
                        sub.setReceiverPhone(data.delivery().phone());
                        sub.setReceiverAddress(data.delivery().address());
                        sub.setRemark(data.delivery().instruction());
                    }
                    subOrders.insert(sub);
                    var allocations = new ArrayList<Allocation>();
                    var nativeAllocations = new ArrayList<IndustryOrderPort.Allocation>();
                    for (var component : data.components()) {
                        var sku = skus.get(component.skuNo());
                        require(sku != null);
                        var item = new OrdItem();
                        store.audit(item, ref.scope());
                        item.setOrderNo(row.getOrderNo());
                        item.setSubOrderNo(row.getSubOrderNo());
                        item.setGoodsNo(component.goodsNo());
                        item.setSkuNo(component.skuNo());
                        item.setTitle(component.title());
                        item.setCover(component.cover());
                        item.setSpec(component.spec());
                        long gross =
                                Math.addExact(component.grossExTaxMinor(), component.taxMinor());
                        item.setQty(1);
                        item.setPrice(gross);
                        item.setAmount(gross);
                        item.setSaleQuantity(component.physicalQuantity());
                        item.setQuantityUnit(component.unit());
                        item.setSourceComponentRef(component.componentRef());
                        item.setCategoryType(sku.categoryType());
                        item.setCategoryNo(sku.categoryNo());
                        item.setSupplyStoreNo(row.getStoreNo());
                        item.setPaidShareMinor(component.netMinor());
                        item.setRefundedQty(0);
                        item.setRefundedMinor(0L);
                        item.setIsGift(false);
                        item.setWeighAdjustMinor(0L);
                        item.setWeighed(false);
                        items.insert(item);
                        String opaque =
                                PricingSnapshotHash.of(
                                        List.of(
                                                "CHANNEL_CORE_ITEM",
                                                ref,
                                                component.componentRef(),
                                                item.getId()));
                        String evidence =
                                PricingSnapshotHash.of(
                                        List.of(ref, component, item.getId(), receipt));
                        allocations.add(
                                new Allocation(
                                        component.externalLineRef(),
                                        component.parentExternalLineRef(),
                                        component.externalKind(),
                                        component.sourceLineRef(),
                                        component.componentRef(),
                                        opaque,
                                        component.physicalQuantity().toPlainString(),
                                        component.unit(),
                                        component.netMinor(),
                                        data.currency(),
                                        evidence,
                                        item.getId()));
                        nativeAllocations.add(
                                new IndustryOrderPort.Allocation(
                                        component.sourceLineRef(),
                                        component.componentRef(),
                                        item.getId(),
                                        1,
                                        component.netMinor(),
                                        data.currency(),
                                        evidence,
                                        component.physicalQuantity(),
                                        component.unit()));
                    }
                    var binding = new OrdIndustryOrderSource();
                    store.audit(binding, ref.scope());
                    binding.setOrderNo(row.getOrderNo());
                    binding.setSubOrderNo(row.getSubOrderNo());
                    binding.setEntityNo(row.getEntityNo());
                    binding.setStoreNo(row.getStoreNo());
                    binding.setBuyerNo("");
                    binding.setActorNo("SYSTEM");
                    binding.setPartyNo(data.party().partyNo());
                    binding.setSourceSystem(ref.sourceSystem());
                    binding.setSourceType("CHANNEL_ORDER");
                    binding.setSourceNo(ref.sourceNo());
                    binding.setSourceVersion(ref.sourceVersion());
                    binding.setSourceHash(ref.sourceHash());
                    binding.setSourceSubjectNo(data.party().partyNo());
                    binding.setQuoteNo(row.getQuoteNo());
                    binding.setRequestHash(ref.sourceHash());
                    binding.setConfirmationDigest(PricingSnapshotHash.of(acceptance));
                    binding.setOrderMode("CHANNEL");
                    binding.setStatus("ACCEPTED");
                    binding.setPartySnapshot(store.json.writeValueAsString(data.party()));
                    binding.setDeliverySnapshot(
                            data.delivery() == null
                                    ? null
                                    : store.json.writeValueAsString(data.delivery()));
                    binding.setDemandPlanNo(plan.planNo());
                    binding.setDemandPlanHash(plan.planHash());
                    binding.setDemandPlanSnapshot(store.json.writeValueAsString(plan));
                    binding.setAllocationsJson(
                            store.json.writeValueAsString(
                                    new BoundAllocations(
                                            nativeAllocations,
                                            List.of(
                                                    new ConfirmationEvidence(
                                                            acceptance, collection, inventory)))));
                    sources.insert(binding);
                    var quote = store.quote(row);
                    require(
                            store.quotes.update(
                                            Wrappers.<OrdChannelQuote>update()
                                                    .eq("tenant_no", row.getTenantNo())
                                                    .eq("id", quote.getId())
                                                    .eq("version", quote.getVersion())
                                                    .set("retained_order_no", row.getOrderNo())
                                                    .setSql("version=version+1"))
                                    == 1);
                    require(
                            store.imports.update(
                                            Wrappers.<OrdChannelImport>update()
                                                    .eq("tenant_no", row.getTenantNo())
                                                    .eq("id", row.getId())
                                                    .eq("version", row.getVersion())
                                                    .set("status", "ACCEPTED")
                                                    .set("acceptance_receipt_ref", receipt)
                                                    .set("lease_until", null)
                                                    .set(
                                                            "platform_acceptance_snapshot",
                                                            store.json.writeValueAsString(
                                                                    new ConfirmationEvidence(
                                                                            acceptance,
                                                                            collection,
                                                                            inventory)))
                                                    .set(
                                                            "allocations_json",
                                                            store.json.writeValueAsString(
                                                                    new StoredAcceptance(
                                                                            allocations)))
                                                    .setSql("version=version+1"))
                                    == 1);
                    var log = new OrdStatusLog();
                    log.setTenantNo(row.getTenantNo());
                    log.setSubOrderNo(row.getSubOrderNo());
                    log.setAggregateType("ORDER");
                    log.setAggregateNo(row.getSubOrderNo());
                    log.setEvent("CHANNEL_ORDER_ACCEPTED");
                    log.setStatus(sub.getStatus());
                    log.setLabel("渠道接单，待履约");
                    log.setOperatorType(OrdStatusLog.BY_SYSTEM);
                    log.setOperatorNo("SYSTEM");
                    log.setAt(store.clock.millis());
                    log.setCreatedAt(store.now());
                    logs.insert(log);
                    events.publish(
                            new OrderEvents.ChannelOrderAccepted(
                                    row.getOrderNo(),
                                    row.getSubOrderNo(),
                                    row.getEntityNo(),
                                    row.getStoreNo(),
                                    ref.sourceSystem(),
                                    ref.sourceNo(),
                                    ref.sourceVersion(),
                                    receipt,
                                    data.currency(),
                                    totals.merchantReceivableMinor()));
                    return accepted(store.required(ref, false), ref);
                });
    }

    Acceptance accepted(OrdChannelImport row, Reference ref) {
        require("ACCEPTED".equals(row.getStatus()) && row.getAcceptanceReceiptRef() != null);
        return new Acceptance(
                ref,
                row.getOrderNo(),
                row.getSubOrderNo(),
                row.getAcceptanceReceiptRef(),
                row.getFundingStatus(),
                store.json
                        .readValue(row.getAllocationsJson(), StoredAcceptance.class)
                        .allocations());
    }

    static String fulfillment(Data data) {
        return switch (data.fulfillment()) {
            case CHANNEL_DELIVERY -> "CHANNEL_DELIVERY";
            case STORE_PICKUP -> "STORE_PICKUP";
            case SHIPMENT -> "EXPRESS";
        };
    }

    private static void require(boolean condition) {
        IndustryChannelOrderStore.require(condition);
    }
}
