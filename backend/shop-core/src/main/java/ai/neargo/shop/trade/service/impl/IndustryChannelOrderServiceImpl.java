package ai.neargo.shop.trade.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort.Plan;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort.Target;
import ai.neargo.shop.spi.product.GoodsQueryPort;
import ai.neargo.shop.spi.product.GoodsQueryPort.SkuSnapshot;
import ai.neargo.shop.spi.trade.IndustryChannelOrderFacts;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort.Acceptance;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort.Cancellation;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort.Preparation;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort.Reference;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort.Scope;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort.Status;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort.Collection;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort.CollectionFacts;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort.Data;
import ai.neargo.shop.spi.trade.IndustryOrderPort;
import ai.neargo.shop.spi.trade.IndustryOrderPort.Result;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import ai.neargo.shop.spi.user.MerchantScopeFactsPort;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import ai.neargo.shop.trade.entity.OrdChannelImport;
import ai.neargo.shop.trade.entity.OrdChannelPolicy;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.order.mapper.PaymentMapper;
import ai.neargo.shop.trade.order.mapper.StoreOrderMapper;
import ai.neargo.shop.trade.service.IndustryChannelOrderService;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Original channel admission coordinates local receipts and independent stock without entering
 * payment checkout.
 */
@Service
public final class IndustryChannelOrderServiceImpl implements IndustryChannelOrderService {
    private final IndustryChannelOrderStore store;
    private final IndustryChannelStockPreparation stock;
    private final IndustryChannelOrderCommitter committer;
    private final ObjectProvider<InventoryDemandPlanPort> inventories;
    private final ObjectProvider<IndustryChannelOrderSourcePort> sources;
    private final MerchantScopeFactsPort owners;
    private final StoreDirectoryPort stores;
    private final MerchantQueryPort merchants;
    private final GoodsQueryPort goods;
    private final PaymentMapper orders;
    private final StoreOrderMapper subOrders;
    private final boolean enabled;

    public IndustryChannelOrderServiceImpl(
            IndustryChannelOrderStore store,
            IndustryChannelStockPreparation stock,
            IndustryChannelOrderCommitter committer,
            ObjectProvider<InventoryDemandPlanPort> inventories,
            ObjectProvider<IndustryChannelOrderSourcePort> sources,
            MerchantScopeFactsPort owners,
            StoreDirectoryPort stores,
            MerchantQueryPort merchants,
            GoodsQueryPort goods,
            PaymentMapper orders,
            StoreOrderMapper subOrders,
            @Value("${shop.trade.channel.enabled:false}") boolean enabled) {
        this.store = store;
        this.stock = stock;
        this.committer = committer;
        this.inventories = inventories;
        this.sources = sources;
        this.owners = owners;
        this.stores = stores;
        this.merchants = merchants;
        this.goods = goods;
        this.orders = orders;
        this.subOrders = subOrders;
        this.enabled = enabled;
    }

    @Override
    public Preparation prepare(Reference ref) {
        IndustryChannelOrderFacts.reference(ref);
        var cancelled = cancelledBeforeAdmission(ref);
        if (cancelled != null) return cancelled;
        var original = store.query(() -> store.find(ref, false));
        if (original != null && terminal(original)) return view(ref, original);
        require(enabled);
        var source = source(ref);
        var facts = source.capture(ref);
        IndustryChannelOrderFacts.validate(ref, facts, store.clock);
        eligibility(facts.data());
        OrdChannelImport row;
        try {
            row = original == null ? store.create(facts) : original;
        } catch (BizException changed) {
            var terminal = cancelledBeforeAdmission(ref);
            if (terminal != null) return terminal;
            throw changed;
        }
        if (Set.of("CANCELLING", "CONFIRMING", "RECOVERY_REQUIRED").contains(row.getStatus()))
            return view(ref, row);
        var collection = source.collection(ref);
        IndustryChannelOrderFacts.collection(ref, facts.data(), collection, store.clock);
        row = store.reserveFunding(ref, facts.data(), collection);
        if (row.getPolicyNo() != null) {
            fundingType(row, collection);
            stock.prepare(ref, source);
            IndustryChannelOrderFacts.validate(ref, facts, store.clock);
            IndustryChannelOrderFacts.collection(ref, facts.data(), collection, store.clock);
        }
        return store.query(() -> view(ref, store.required(ref, false)));
    }

    @Override
    public Preparation get(Reference ref) {
        IndustryChannelOrderFacts.reference(ref);
        var cancelled = cancelledBeforeAdmission(ref);
        if (cancelled != null) return cancelled;
        var row = store.query(() -> store.find(ref, false));
        if (row == null) return null;
        if (terminal(row)) return view(ref, row);
        if (!enabled) throw BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED);
        // A positive readiness response must refresh original source and original stock facts.
        return prepare(ref);
    }

    @Override
    public Acceptance confirm(Reference ref) {
        IndustryChannelOrderFacts.reference(ref);
        var original = store.query(() -> store.required(ref, false));
        if ("ACCEPTED".equals(original.getStatus())) return committer.accepted(original, ref);
        require(enabled);
        var source = source(ref);
        var facts = source.capture(ref);
        IndustryChannelOrderFacts.validate(ref, facts, store.clock);
        var skus = eligibility(facts.data());
        var merchant =
                merchants
                        .find(ref.scope().entityNo())
                        .filter(m -> m.canSell())
                        .orElseThrow(() -> BizException.of(ErrorCode.CONFLICT));
        var acceptance = source.acceptance(ref);
        var collection = source.collection(ref);
        IndustryChannelOrderFacts.acceptance(ref, facts.data(), acceptance, store.clock);
        IndustryChannelOrderFacts.collection(ref, facts.data(), collection, store.clock);
        var admitted = store.reserveFunding(ref, facts.data(), collection);
        fundingType(admitted, collection);
        long fence =
                store.atomic(
                        () -> {
                            store.activeGuard(ref);
                            var row = store.required(ref, true);
                            require(
                                    Set.of("PREPARED", "CONFIRMING").contains(row.getStatus())
                                            && (row.getLeaseUntil() == null
                                                    || !row.getLeaseUntil().isAfter(store.now())));
                            var prep = store.preparation(row, true);
                            require(prep != null && prep.getPlanSnapshot() != null);
                            long next = Math.addExact(row.getFence(), 1);
                            require(
                                    store.imports.update(
                                                    Wrappers.<OrdChannelImport>update()
                                                            .eq("tenant_no", row.getTenantNo())
                                                            .eq("id", row.getId())
                                                            .eq("version", row.getVersion())
                                                            .set("status", "CONFIRMING")
                                                            .set("fence", next)
                                                            .set(
                                                                    "lease_until",
                                                                    store.now().plusMinutes(2))
                                                            .set(
                                                                    "platform_acceptance_snapshot",
                                                                    store.json.writeValueAsString(
                                                                            acceptance))
                                                            .setSql("version=version+1"))
                                            == 1);
                            return next;
                        });
        var plan = store.query(() -> plan(store.required(ref, false)));
        var inventory = inventory();
        var receipt = inventory.query(plan.scope());
        IndustryChannelStockPreparation.validateReceipt(plan, receipt);
        if ("HELD".equals(receipt.status())) {
            require(
                    receipt.expiresAt() != null
                            && receipt.expiresAt().isAfter(store.clock.instant()));
            require(
                    store.query(
                            () -> {
                                var current = store.required(ref, false);
                                return current.getFence() == fence
                                        && "CONFIRMING".equals(current.getStatus());
                            }));
            receipt = inventory.acceptUnpaid(plan.scope());
        }
        return committer.commit(
                ref, fence, plan, receipt, acceptance, collection, skus, merchant.merchantName());
    }

    @Override
    public Cancellation cancel(Reference ref) {
        IndustryChannelOrderFacts.reference(ref);
        var prior = store.query(() -> store.guard(ref, false));
        if (prior != null && "CANCELLED".equals(prior.getStatus()))
            return new Cancellation(ref, prior.getCancellationReceiptRef());
        var original = store.query(() -> store.find(ref, false));
        if (original != null && "CANCELLED".equals(original.getStatus()))
            return new Cancellation(ref, original.getCancellationReceiptRef());
        require(original == null || !"ACCEPTED".equals(original.getStatus()));
        var withdrawal = source(ref).withdrawal(ref);
        require(withdrawal != null && ref.equals(withdrawal.reference()));
        IndustryChannelOrderFacts.freshness(
                withdrawal.observedAt(), withdrawal.validUntil(), store.clock);
        IndustryChannelOrderFacts.key(withdrawal.reasonCode(), 32);
        IndustryChannelOrderFacts.key(withdrawal.originalWithdrawalRef(), 256);
        IndustryChannelOrderFacts.key(withdrawal.receiptRef(), 256);
        var absentCancellation = store.beginCancellation(ref, withdrawal);
        if (absentCancellation != null) return absentCancellation;
        var claim =
                store.atomic(
                        () -> {
                            var row = store.required(ref, true);
                            require(!terminal(row));
                            if ("CANCELLING".equals(row.getStatus())
                                    && row.getLeaseUntil() != null
                                    && row.getLeaseUntil().isAfter(store.now()))
                                throw BizException.of(ErrorCode.CONFLICT);
                            long fence = Math.addExact(row.getFence(), 1);
                            require(
                                    store.imports.update(
                                                    Wrappers.<OrdChannelImport>update()
                                                            .eq("tenant_no", row.getTenantNo())
                                                            .eq("id", row.getId())
                                                            .eq("version", row.getVersion())
                                                            .set("status", "CANCELLING")
                                                            .set("fence", fence)
                                                            .set(
                                                                    "lease_until",
                                                                    store.now().plusMinutes(2))
                                                            .setSql("version=version+1"))
                                            == 1);
                            return store.required(ref, false);
                        });
        var plan = store.query(() -> plan(claim));
        Object stockProof = null;
        if (plan != null) {
            var inventory = inventory();
            var receipt = inventory.query(plan.scope());
            if (!"ABSENT".equals(receipt.status())) {
                IndustryChannelStockPreparation.validateReceipt(plan, receipt);
                if ("ACCEPTED_UNPAID".equals(receipt.status())) {
                    // The core cancellation fence prevents final order creation. Original stock
                    // owner
                    // alone proves no issuance and releases the exact plan before receivable credit
                    // is returned.
                    for (var line : receipt.lines()) require(line.issuedQty().signum() == 0);
                    var targets =
                            receipt.lines().stream()
                                    .map(line -> new Target(line.leafRef(), line.originalQty()))
                                    .toList();
                    var released =
                            inventory.releaseTo(
                                    plan.scope(),
                                    claim.getAdmissionNo() + "-CANCEL",
                                    plan.planHash(),
                                    targets);
                    IndustryChannelStockPreparation.validateReceipt(plan, released);
                    for (var line : released.lines())
                        require(
                                line.issuedQty().signum() == 0
                                        && line.releasedQty().compareTo(line.originalQty()) == 0);
                }
            }
            stockProof = inventory.withdraw(plan, claim.getAdmissionNo() + "-WITHDRAW", 3);
            var proof = (InventoryDemandPlanPort.WithdrawalReceipt) stockProof;
            require(
                    plan.scope().equals(proof.scope())
                            && plan.planNo().equals(proof.planNo())
                            && plan.planHash().equals(proof.planHash())
                            && proof.status() != null
                            && (claim.getAdmissionNo() + "-WITHDRAW").equals(proof.operationRef()));
            IndustryChannelOrderFacts.key(proof.receiptRef(), 256);
        }
        var finalProof = stockProof;
        return store.atomic(
                () -> {
                    // Same lock order as admission/configuration: policy first, then import.
                    var policy =
                            claim.getPolicyNo() == null
                                    ? null
                                    : store.policies.selectOne(
                                            Wrappers.<OrdChannelPolicy>query()
                                                    .eq("tenant_no", ref.scope().tenantNo())
                                                    .eq("entity_no", ref.scope().entityNo())
                                                    .eq("store_no", ref.scope().storeNo())
                                                    .eq("policy_no", claim.getPolicyNo())
                                                    .last("FOR UPDATE"));
                    var cancellationGuard = store.guard(ref, true);
                    require(
                            cancellationGuard != null
                                    && "CANCELLING".equals(cancellationGuard.getStatus()));
                    var row = store.required(ref, true);
                    require(
                            "CANCELLING".equals(row.getStatus())
                                    && row.getFence().equals(claim.getFence()));
                    if (row.getPolicyNo() != null) {
                        require(
                                policy != null
                                        && policy.getReservedMinor() >= row.getReservedMinor());
                        require(
                                store.policies.update(
                                                Wrappers.<OrdChannelPolicy>update()
                                                        .eq("tenant_no", row.getTenantNo())
                                                        .eq("id", policy.getId())
                                                        .eq("version", policy.getVersion())
                                                        .set(
                                                                "reserved_minor",
                                                                Math.subtractExact(
                                                                        policy.getReservedMinor(),
                                                                        row.getReservedMinor()))
                                                        .setSql("version=version+1"))
                                        == 1);
                    }
                    String receipt = BizKey.next("OCX");
                    store.finishCancellation(ref, receipt);
                    require(
                            store.imports.update(
                                            Wrappers.<OrdChannelImport>update()
                                                    .eq("tenant_no", row.getTenantNo())
                                                    .eq("id", row.getId())
                                                    .eq("version", row.getVersion())
                                                    .set("status", "CANCELLED")
                                                    .set("funding_status", "RELEASED")
                                                    .set("reserved_minor", 0L)
                                                    .set("lease_until", null)
                                                    .set("cancellation_receipt_ref", receipt)
                                                    .set(
                                                            "platform_acceptance_snapshot",
                                                            store.json.writeValueAsString(
                                                                    new CancellationEvidence(
                                                                            withdrawal,
                                                                            finalProof)))
                                                    .setSql("version=version+1"))
                                    == 1);
                    return new Cancellation(ref, receipt);
                });
    }

    record CancellationEvidence(
            IndustryChannelOrderSourcePort.Withdrawal withdrawal, Object inventory) {}

    @Override
    public Result industryResult(Scope scope, String orderNo) {
        IndustryChannelOrderFacts.scope(scope);
        IndustryChannelOrderFacts.key(orderNo, 32);
        return store.query(
                () -> {
                    var row =
                            store.imports.selectOne(
                                    Wrappers.<OrdChannelImport>query()
                                            .eq("tenant_no", scope.tenantNo())
                                            .eq("entity_no", scope.entityNo())
                                            .eq("store_no", scope.storeNo())
                                            .eq("order_no", orderNo));
                    if (row == null) throw BizException.of(ErrorCode.NOT_FOUND);
                    require("ACCEPTED".equals(row.getStatus()));
                    var order =
                            orders.selectOne(
                                    Wrappers.<OrdOrder>query()
                                            .eq("tenant_no", scope.tenantNo())
                                            .eq("order_no", orderNo));
                    var sub =
                            subOrders.selectOne(
                                    Wrappers.<OrdSubOrder>query()
                                            .eq("tenant_no", scope.tenantNo())
                                            .eq("entity_no", scope.entityNo())
                                            .eq("store_no", scope.storeNo())
                                            .eq("sub_order_no", row.getSubOrderNo()));
                    require(
                            order != null
                                    && OrdOrder.WAIT_CHANNEL_PAY.equals(order.getStatus())
                                    && order.getPaidAt() == null
                                    && order.getPayTradeNo() == null
                                    && sub != null
                                    && Integer.valueOf(3)
                                            .equals(sub.getInventoryLifecycleVersion()));
                    var allocations =
                            store
                                    .json
                                    .readValue(
                                            row.getAllocationsJson(),
                                            IndustryChannelOrderCommitter.StoredAcceptance.class)
                                    .allocations()
                                    .stream()
                                    .map(
                                            a ->
                                                    new IndustryOrderPort.Allocation(
                                                            a.sourceLineRef(),
                                                            a.componentRef(),
                                                            a.orderItemId(),
                                                            1,
                                                            a.amountMinor(),
                                                            a.currency(),
                                                            a.receiptRef(),
                                                            new BigDecimal(a.physicalQuantity()),
                                                            a.unit()))
                                    .toList();
                    return new Result(
                            orderNo,
                            row.getSubOrderNo(),
                            row.getQuoteNo(),
                            order.getStatus(),
                            order.getCurrency(),
                            order.getPayAmount(),
                            row.getSourceVersion(),
                            3,
                            allocations,
                            "CHANNEL",
                            sub.getStatus(),
                            false,
                            plan(row),
                            null,
                            null);
                });
    }

    private Preparation cancelledBeforeAdmission(Reference ref) {
        return store.query(
                () -> {
                    var guard = store.guard(ref, false);
                    if (guard == null || !"CANCELLED".equals(guard.getStatus())) return null;
                    var row = store.find(ref, false);
                    if (row != null) return view(ref, row);
                    return new Preparation(
                            ref, Status.CANCELLED, null, null, null, null, "RELEASED", null, null);
                });
    }

    private Preparation view(Reference ref, OrdChannelImport row) {
        return store.query(
                () -> {
                    var prep = store.preparation(row, false);
                    var guard = store.guard(ref, false);
                    boolean cancelling = guard != null && "CANCELLING".equals(guard.getStatus());
                    return new Preparation(
                            ref,
                            cancelling ? Status.CANCELLING : Status.valueOf(row.getStatus()),
                            row.getOrderNo(),
                            row.getSubOrderNo(),
                            cancelling || prep == null ? null : prep.getPreparationReceiptRef(),
                            cancelling || prep == null || prep.getValidUntil() == null
                                    ? null
                                    : prep.getValidUntil().toInstant(ZoneOffset.UTC),
                            row.getFundingStatus(),
                            plan(row),
                            row.getQuoteNo());
                });
    }

    private Plan plan(OrdChannelImport row) {
        var prep = store.preparation(row, false);
        return prep == null || prep.getPlanSnapshot() == null
                ? null
                : store.json.readValue(prep.getPlanSnapshot(), Plan.class);
    }

    private IndustryChannelOrderSourcePort source(Reference ref) {
        var matches =
                sources.stream().filter(p -> ref.sourceSystem().equals(p.sourceSystem())).toList();
        if (matches.size() != 1) throw BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED);
        return matches.getFirst();
    }

    private Map<String, SkuSnapshot> eligibility(Data data) {
        var scope = data.scope();
        require(
                owners.entityActive(scope.tenantNo(), scope.entityNo())
                        && owners.storesOwned(
                                scope.tenantNo(), scope.entityNo(), Set.of(scope.storeNo())));
        var card = stores.cards(List.of(scope.storeNo())).get(scope.storeNo());
        require(card != null && card.active() && scope.entityNo().equals(card.entityNo()));
        require(merchants.find(scope.entityNo()).filter(m -> m.canSell()).isPresent());
        var snapshots =
                goods.snapshot(
                        data.components().stream().map(c -> c.skuNo()).distinct().toList(),
                        Map.of(scope.entityNo(), scope.storeNo()));
        for (var component : data.components()) {
            var sku = snapshots.get(component.skuNo());
            require(
                    sku != null
                            && component.goodsNo().equals(sku.goodsNo())
                            && scope.entityNo().equals(sku.merchantNo())
                            && scope.storeNo().equals(sku.storeNo())
                            && sku.onSale()
                            && !sku.limited()
                            && Set.of("NORMAL", "FRESH").contains(sku.categoryType())
                            && (sku.saleMode() == null
                                    || "NORMAL".equals(sku.saleMode())
                                    || sku.activityOnly()
                                            && "BUNDLE_MEMBER".equals(component.externalKind()))
                            && (data.fulfillment()
                                            == IndustryChannelOrderSourcePort.Fulfillment
                                                    .CHANNEL_DELIVERY
                                    || sku.fulfillments() != null
                                            && sku.fulfillments()
                                                    .contains(
                                                            IndustryChannelOrderCommitter
                                                                    .fulfillment(data))));
        }
        return snapshots;
    }

    private void fundingType(OrdChannelImport row, CollectionFacts collection) {
        require(row.getPolicySnapshot() != null);
        var grant =
                store.json.readValue(
                        row.getPolicySnapshot(), IndustryChannelOrderStore.FundingGrant.class);
        // A changed collection kind must already have its own persisted current-policy admission.
        require(
                grant.originalCollection().collection() == collection.collection()
                        && Set.of(Collection.PLATFORM_COLLECTED, Collection.CASH_ON_DELIVERY)
                                .contains(collection.collection()));
    }

    private InventoryDemandPlanPort inventory() {
        var providers = inventories.orderedStream().limit(2).toList();
        if (providers.size() != 1) throw BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED);
        return providers.getFirst();
    }

    private static boolean terminal(OrdChannelImport row) {
        return Set.of("ACCEPTED", "CANCELLED").contains(row.getStatus());
    }

    private static void require(boolean condition) {
        IndustryChannelOrderStore.require(condition);
    }
}
