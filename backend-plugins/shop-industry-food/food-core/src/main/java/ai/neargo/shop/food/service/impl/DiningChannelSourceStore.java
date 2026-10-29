package ai.neargo.shop.food.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BaseEntity;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.food.dto.DiningChannelSourceFacts;
import ai.neargo.shop.food.dto.DiningChannelSourceFacts.Key;
import ai.neargo.shop.food.dto.KitchenContracts.FrozenRoutes;
import ai.neargo.shop.food.dto.OrderLineInput;
import ai.neargo.shop.food.dto.SalesQuotaClaimSource;
import ai.neargo.shop.food.dto.SalesQuotaClaimVO;
import ai.neargo.shop.food.dto.StoreOperationPolicyResultVO;
import ai.neargo.shop.food.entity.FnbChannelImportGuard;
import ai.neargo.shop.food.entity.FnbChannelOrderSource;
import ai.neargo.shop.food.entity.FnbChannelParty;
import ai.neargo.shop.food.entity.FnbCheckCart;
import ai.neargo.shop.food.entity.FnbCheckCartItem;
import ai.neargo.shop.food.entity.FnbOrderBatch;
import ai.neargo.shop.food.lifecycle.ServiceSlotLifecycle;
import ai.neargo.shop.food.mapper.DiningChannelMappers.ChannelImportGuardMapper;
import ai.neargo.shop.food.mapper.DiningChannelMappers.ChannelOrderSourceMapper;
import ai.neargo.shop.food.mapper.DiningChannelMappers.ChannelPartyMapper;
import ai.neargo.shop.food.mapper.FoodMappers.CheckCartItemMapper;
import ai.neargo.shop.food.mapper.FoodMappers.CheckCartMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OrderBatchMapper;
import ai.neargo.shop.food.policy.ServiceCalendarPolicy;
import ai.neargo.shop.food.service.DiningChannelOrderService.CancellationResult;
import ai.neargo.shop.food.service.SalesQuotaClaimService;
import ai.neargo.shop.food.spi.DiningChannelSourceFactsPort.CurrentEvidence;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort.Plan;
import ai.neargo.shop.spi.trade.IndustryChannelOrderFacts;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort.Acceptance;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort.Cancellation;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort.Preparation;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort.Reference;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort.Scope;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort.Status;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort.Component;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort.Data;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort.Facts;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort.Fulfillment;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort.Party;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Local FOOD persistence owns genuine party/cart/source numbers; all external facts are read before
 * these transactions.
 */
public final class DiningChannelSourceStore {
    final ChannelOrderSourceMapper sources;
    final ChannelImportGuardMapper guards;
    final ChannelPartyMapper parties;
    final CheckCartMapper carts;
    final CheckCartItemMapper items;
    final OrderBatchMapper batches;
    final SalesQuotaClaimService quotas;
    final ServiceSlotLifecycle slots;
    final ObjectMapper json;
    final Clock clock;
    private final TransactionTemplate read;
    private final TransactionTemplate write;

    public DiningChannelSourceStore(
            ChannelOrderSourceMapper sources,
            ChannelImportGuardMapper guards,
            ChannelPartyMapper parties,
            CheckCartMapper carts,
            CheckCartItemMapper items,
            OrderBatchMapper batches,
            SalesQuotaClaimService quotas,
            ObjectMapper json,
            PlatformTransactionManager manager,
            Clock clock) {
        this(sources, guards, parties, carts, items, batches, quotas, json, manager, clock, null);
    }

    public DiningChannelSourceStore(
            ChannelOrderSourceMapper sources,
            ChannelImportGuardMapper guards,
            ChannelPartyMapper parties,
            CheckCartMapper carts,
            CheckCartItemMapper items,
            OrderBatchMapper batches,
            SalesQuotaClaimService quotas,
            ObjectMapper json,
            PlatformTransactionManager manager,
            Clock clock,
            ServiceSlotLifecycle slots) {
        this.slots = slots;
        this.sources = sources;
        this.guards = guards;
        this.parties = parties;
        this.carts = carts;
        this.items = items;
        this.batches = batches;
        this.quotas = quotas;
        this.json = json;
        this.clock = clock;
        read = new TransactionTemplate(manager);
        read.setReadOnly(true);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        write = new TransactionTemplate(manager);
        write.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
    }

    record CartEnvelope(int schema, OrderLineInput line) {}

    record FrozenSource(
            int schema,
            String sourceNo,
            String sourceHash,
            DiningChannelSourceFacts.Original original,
            List<OrderLineInput> cartLines,
            StoreOperationPolicyResultVO policy,
            FrozenRoutes kitchenRoutes,
            List<SalesQuotaClaimVO> quotaClaims,
            Plan inventoryPlan) {
        FrozenSource {
            cartLines = List.copyOf(cartLines);
            quotaClaims = List.copyOf(quotaClaims);
        }

        FrozenSource withPlan(Plan plan) {
            return new FrozenSource(
                    schema,
                    sourceNo,
                    sourceHash,
                    original,
                    cartLines,
                    policy,
                    kitchenRoutes,
                    quotaClaims,
                    plan);
        }
    }

    <T> T query(Supplier<T> work) {
        return DataScopeContext.executeWithoutScope(() -> read.execute(tx -> work.get()));
    }

    <T> T atomic(Supplier<T> work) {
        return DataScopeContext.executeWithoutScope(() -> write.execute(tx -> work.get()));
    }

    LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    void stamp(BaseEntity row, String entity) {
        row.setTenantNo("MAIN");
        row.setVersion(0L);
        row.setDeleted(0);
        row.setCreatedAt(now());
        row.setUpdatedAt(now());
        row.setCreatedBy("SYSTEM");
        row.setUpdatedBy("SYSTEM");
    }

    FnbChannelImportGuard guard(Key key, boolean lock) {
        var query =
                Wrappers.<FnbChannelImportGuard>query()
                        .eq("tenant_no", "MAIN")
                        .eq("entity_no", key.entityNo())
                        .eq("store_no", key.storeNo())
                        .eq("import_no", key.importNo());
        if (lock) query.last("FOR UPDATE");
        var guard = guards.selectOne(query);
        if (guard != null)
            require(key.originalDocumentHash().equals(guard.getOriginalDocumentHash()));
        return guard;
    }

    void ensureGuard(Key key) {
        try {
            atomic(
                    () -> {
                        if (guard(key, true) != null) return null;
                        var row = new FnbChannelImportGuard();
                        stamp(row, key.entityNo());
                        row.setEntityNo(key.entityNo());
                        row.setStoreNo(key.storeNo());
                        row.setGuardNo(BizKey.next("FCIG"));
                        row.setImportNo(key.importNo());
                        row.setOriginalDocumentHash(key.originalDocumentHash());
                        row.setStatus("ACTIVE");
                        guards.insert(row);
                        return null;
                    });
        } catch (DuplicateKeyException concurrent) {
            require(query(() -> guard(key, false)) != null);
        }
    }

    void activeGuard(Key key) {
        var guard = guard(key, true);
        require(guard != null && "ACTIVE".equals(guard.getStatus()));
    }

    CancellationResult cancelled(Key key, FnbChannelImportGuard guard) {
        require(
                guard != null
                        && "CANCELLED".equals(guard.getStatus())
                        && guard.getCancellationReceiptRef() != null);
        return new CancellationResult(
                key,
                guard.getCancellationReceiptRef(),
                guard.getCoreCancellationSnapshot() == null
                        ? null
                        : json.readValue(guard.getCoreCancellationSnapshot(), Cancellation.class));
    }

    CancellationResult beginCancellation(Key key, CurrentEvidence withdrawal) {
        ensureGuard(key);
        return atomic(
                () -> {
                    var guard = guard(key, true);
                    if ("CANCELLED".equals(guard.getStatus())) return cancelled(key, guard);
                    require(!"AFTER_SALE".equals(guard.getStatus()));
                    var source = find(key, true);
                    require(source == null || source.getAcceptanceReceiptRef() == null);
                    String receipt = source == null ? BizKey.next("FCX") : null;
                    require(
                            guards.update(
                                            Wrappers.<FnbChannelImportGuard>update()
                                                    .eq("tenant_no", "MAIN")
                                                    .eq("id", guard.getId())
                                                    .eq("version", guard.getVersion())
                                                    .set(
                                                            "status",
                                                            source == null
                                                                    ? "CANCELLED"
                                                                    : "CANCELLING")
                                                    .set(
                                                            "withdrawal_snapshot",
                                                            json.writeValueAsString(withdrawal))
                                                    .set("cancellation_receipt_ref", receipt)
                                                    .setSql("version=version+1"))
                                    == 1);
                    return receipt == null ? null : cancelled(key, guard(key, false));
                });
    }

    FnbChannelOrderSource find(Key key, boolean lock) {
        var query =
                Wrappers.<FnbChannelOrderSource>query()
                        .eq("tenant_no", "MAIN")
                        .eq("entity_no", key.entityNo())
                        .eq("store_no", key.storeNo())
                        .eq("import_no", key.importNo());
        if (lock) query.last("FOR UPDATE");
        var row = sources.selectOne(query);
        if (row != null) require(row.getOriginalDocumentHash().equals(key.originalDocumentHash()));
        return row;
    }

    FnbChannelOrderSource required(Key key, boolean lock) {
        var row = find(key, lock);
        if (row == null) throw BizException.of(ErrorCode.NOT_FOUND);
        return row;
    }

    Reference reference(FnbChannelOrderSource source) {
        return new Reference(
                new Scope("MAIN", source.getEntityNo(), source.getStoreNo()),
                "FOOD",
                source.getSourceNo(),
                source.getSourceVersion(),
                source.getSourceHash(),
                source.getImportNo());
    }

    Data data(FnbChannelOrderSource source) {
        var data = json.readValue(source.getSourceSnapshot(), Data.class);
        require(source.getSourceHash().equals(PricingSnapshotHash.of(data)));
        return data;
    }

    FrozenSource frozen(FnbChannelOrderSource source) {
        var frozen = json.readValue(source.getEnvelopeSnapshot(), FrozenSource.class);
        require(
                frozen.schema() == 1
                        && source.getSourceNo().equals(frozen.sourceNo())
                        && source.getSourceHash().equals(frozen.sourceHash()));
        return frozen;
    }

    /**
     * Sources and quota claims are genuine even before core assigns its genuine channel quote and
     * order numbers.
     */
    FnbChannelOrderSource register(
            DiningChannelSourceFacts facts,
            StoreOperationPolicyResultVO policy,
            FrozenRoutes routes) {
        ensureGuard(facts.key());
        try {
            return atomic(
                    () -> {
                        var key = facts.key();
                        activeGuard(key);
                        var prior = find(key, true);
                        if (prior != null) return prior;
                        IndustryChannelOrderFacts.freshness(
                                facts.observedAt(), facts.validUntil(), clock);
                        require(
                                facts.original() != null
                                        && facts.current() != null
                                        && policy != null
                                        && routes != null
                                        && key.originalDocumentHash()
                                                .equals(facts.original().documentHash())
                                        && facts.original()
                                                .externalOrderRef()
                                                .equals(facts.current().externalOrderRef())
                                        && facts.original()
                                                .financialHash()
                                                .equals(facts.current().financialHash())
                                        && facts.current().state()
                                                != DiningChannelSourceFacts.State.CANCELLED
                                        && facts.current().state()
                                                != DiningChannelSourceFacts.State.COMPLETED
                                        && facts.original().fulfillment()
                                                == Fulfillment.CHANNEL_DELIVERY
                                        && !facts.original().lines().isEmpty()
                                        && facts.original().lines().size() <= 300);
                        var original = facts.original();
                        var source = new FnbChannelOrderSource();
                        stamp(source, key.entityNo());
                        source.setSourceNo(BizKey.next("FCS"));
                        source.setEntityNo(key.entityNo());
                        source.setStoreNo(key.storeNo());
                        source.setImportNo(key.importNo());
                        source.setChannelStoreKey(original.channel().channelStoreKey());
                        source.setExternalOrderRef(original.externalOrderRef());
                        source.setExternalOrderKey(
                                PricingSnapshotHash.of(original.externalOrderRef()));
                        source.setOriginalDocumentHash(key.originalDocumentHash());
                        source.setOriginalFinancialHash(original.financialHash());
                        source.setConnectionNo(original.connectionNo());
                        source.setConnectionRevisionNo(original.connectionRevisionNo());
                        source.setExternalSnapshotNo(original.externalSnapshotNo());
                        source.setSourceVersion(1L);
                        source.setCartNo(BizKey.next("FCAR"));
                        source.setBatchNo(BizKey.next("FBAT"));
                        var party = party(key, original);
                        source.setPartyNo(party.getPartyNo());
                        var cart = new FnbCheckCart();
                        stamp(cart, key.entityNo());
                        cart.setEntityNo(key.entityNo());
                        cart.setCartNo(source.getCartNo());
                        cart.setStoreNo(key.storeNo());
                        cart.setOrderSource("CHANNEL");
                        cart.setFulfillment("DELIVERY");
                        cart.setStatus("LOCKED");
                        cart.setScheduledAt(
                                original.scheduledAt() == null
                                        ? null
                                        : original.scheduledAt()
                                                .atOffset(ZoneOffset.UTC)
                                                .toLocalDateTime());
                        carts.insert(cart);
                        if (original.scheduledAt() != null) {
                            require(slots != null);
                            slots.reserveScheduledCart(
                                    key.entityNo(),
                                    key.storeNo(),
                                    "SYSTEM",
                                    cart.getCartNo(),
                                    original.scheduledAt(),
                                    "CHANNEL_SLOT:" + source.getSourceNo());
                        }
                        var selected = new ArrayList<OrderLineInput>();
                        var components = new ArrayList<Component>();
                        for (var line : original.lines()) {
                            String lineRef = BizKey.next("FCSL");
                            var input =
                                    new OrderLineInput(
                                            lineRef,
                                            line.menuNo(),
                                            line.menuItemNo(),
                                            line.menuRevisionNo(),
                                            line.goodsNo(),
                                            line.skuNo(),
                                            line.quantity(),
                                            null,
                                            null,
                                            line.selections(),
                                            line.bundleChoices());
                            selected.add(input);
                            var item = new FnbCheckCartItem();
                            stamp(item, key.entityNo());
                            item.setEntityNo(key.entityNo());
                            item.setCartNo(source.getCartNo());
                            item.setCartItemNo(BizKey.next("FCIT"));
                            item.setSourceLineRef(lineRef);
                            item.setGoodsNo(line.goodsNo());
                            item.setSkuNo(line.skuNo());
                            item.setQty(line.quantity());
                            item.setSelection(json.writeValueAsString(new CartEnvelope(1, input)));
                            item.setAddedBy("SYSTEM");
                            items.insert(item);
                            for (var component : line.components()) {
                                String componentRef =
                                        PricingSnapshotHash.of(
                                                List.of(
                                                        source.getSourceNo(),
                                                        lineRef,
                                                        component.logicalKey()));
                                components.add(
                                        new Component(
                                                component.externalLineRef(),
                                                component.parentExternalLineRef(),
                                                component.externalKind(),
                                                lineRef,
                                                componentRef,
                                                component.productionKind(),
                                                component.goodsNo(),
                                                component.skuNo(),
                                                component.title(),
                                                component.cover(),
                                                component.spec(),
                                                component.physicalQuantity(),
                                                component.unit(),
                                                component.grossExTaxMinor(),
                                                component.merchantDiscountExTaxMinor(),
                                                component.taxMinor(),
                                                component.netMinor(),
                                                component.originalAllocationBasisRef()));
                            }
                        }
                        var data =
                                new Data(
                                        new Scope("MAIN", key.entityNo(), key.storeNo()),
                                        "FOOD",
                                        source.getSourceNo(),
                                        1,
                                        key.importNo(),
                                        original.channel(),
                                        original.externalOrderRef(),
                                        original.documentHash(),
                                        original.originalReadReceiptRef(),
                                        original.financialHash(),
                                        original.currency(),
                                        original.originalCollection(),
                                        original.originalCollectionRef(),
                                        original.fulfillment(),
                                        new Party(
                                                party.getPartyNo(),
                                                original.party().externalCustomerRef(),
                                                original.party().displayName()),
                                        original.delivery(),
                                        original.orderedAt(),
                                        original.invoiceLines(),
                                        components,
                                        original.totals());
                        source.setSourceHash(PricingSnapshotHash.of(data));
                        source.setSourceSnapshot(json.writeValueAsString(data));
                        IndustryChannelOrderFacts.validate(
                                reference(source),
                                new Facts(
                                        reference(source),
                                        data,
                                        facts.observedAt(),
                                        facts.validUntil(),
                                        facts.sourceReceiptRef()),
                                clock);
                        var quota =
                                quotas.reserve(
                                        key.entityNo(),
                                        key.storeNo(),
                                        source.getBatchNo(),
                                        "CHANNEL",
                                        "DELIVERY",
                                        components.stream()
                                                .map(
                                                        component ->
                                                                new SalesQuotaClaimSource(
                                                                        component.componentRef(),
                                                                        component.skuNo(),
                                                                        component
                                                                                .physicalQuantity()))
                                                .toList());
                        source.setEnvelopeSnapshot(
                                json.writeValueAsString(
                                        new FrozenSource(
                                                1,
                                                source.getSourceNo(),
                                                source.getSourceHash(),
                                                original,
                                                selected,
                                                policy,
                                                routes,
                                                quota,
                                                null)));
                        source.setStatus("REGISTERED");
                        source.setFence(0L);
                        sources.insert(source);
                        IndustryChannelOrderFacts.freshness(
                                facts.observedAt(), facts.validUntil(), clock);
                        return source;
                    });
        } catch (DuplicateKeyException duplicate) {
            var prior = query(() -> find(facts.key(), false));
            if (prior == null) throw BizException.of(ErrorCode.CONFLICT);
            return prior;
        }
    }

    private FnbChannelParty party(Key key, DiningChannelSourceFacts.Original original) {
        require(original.party() != null);
        String external = original.party().externalCustomerRef();
        if (external != null) IndustryChannelOrderFacts.key(external, 128);
        String identity =
                PricingSnapshotHash.of(
                        external == null
                                ? List.of("CHANNEL_ORDER", original.externalOrderRef())
                                : List.of("EXTERNAL_CUSTOMER", external));
        var party =
                parties.selectOne(
                        Wrappers.<FnbChannelParty>query()
                                .eq("tenant_no", "MAIN")
                                .eq("entity_no", key.entityNo())
                                .eq("store_no", key.storeNo())
                                .eq("channel_store_key", original.channel().channelStoreKey())
                                .eq("party_key", identity)
                                .last("FOR UPDATE"));
        if (party != null) return party;
        party = new FnbChannelParty();
        stamp(party, key.entityNo());
        party.setEntityNo(key.entityNo());
        party.setStoreNo(key.storeNo());
        party.setPartyNo(BizKey.next("FCP"));
        party.setChannelStoreKey(original.channel().channelStoreKey());
        party.setPartyKey(identity);
        party.setExternalCustomerRef(external);
        party.setDisplayName(original.party().displayName());
        party.setOriginalExternalOrderRef(original.externalOrderRef());
        parties.insert(party);
        return party;
    }

    /**
     * The existing batch's non-null quote reference is filled only with an actual core-owned OCQ
     * quote.
     */
    FnbOrderBatch recordPreparation(Key key, Preparation preparation) {
        return atomic(
                () -> {
                    activeGuard(key);
                    var source = required(key, true);
                    require(
                            reference(source).equals(preparation.reference())
                                    && preparation.quoteNo() != null
                                    && preparation.orderNo() != null
                                    && preparation.subOrderNo() != null
                                    && !List.of("CANCELLING", "CANCELLED")
                                            .contains(source.getStatus())
                                    && preparation.status() != Status.CANCELLED
                                    && preparation.status() != Status.CANCELLING);
                    var previous =
                            batches.selectOne(
                                    Wrappers.<FnbOrderBatch>query()
                                            .eq("tenant_no", "MAIN")
                                            .eq("entity_no", key.entityNo())
                                            .eq("store_no", key.storeNo())
                                            .eq("batch_no", source.getBatchNo()));
                    if (previous != null) {
                        require(
                                previous.getQuoteRef().equals(preparation.quoteNo())
                                        && previous.getQuoteHash().equals(source.getSourceHash()));
                        return previous;
                    }
                    var frozen = frozen(source).withPlan(preparation.inventoryPlan());
                    var batch = new FnbOrderBatch();
                    stamp(batch, key.entityNo());
                    batch.setEntityNo(key.entityNo());
                    batch.setStoreNo(key.storeNo());
                    batch.setBatchNo(source.getBatchNo());
                    batch.setCartNo(source.getCartNo());
                    batch.setCartVersion(0L);
                    batch.setRequestKey("CHANNEL:" + source.getImportNo());
                    batch.setRequestHash(source.getSourceHash());
                    batch.setSource("CHANNEL");
                    batch.setPartyNo(source.getPartyNo());
                    batch.setOperatorNo("SYSTEM");
                    batch.setActorRealm("SYSTEM");
                    batch.setStatus("PREPARED");
                    batch.setQuoteRef(preparation.quoteNo());
                    batch.setQuoteHash(source.getSourceHash());
                    batch.setFrozenRequest(json.writeValueAsString(frozen));
                    batch.setBusinessDate(
                            ServiceCalendarPolicy.businessDate(
                                    frozen.original().orderedAt(), frozen.policy()));
                    batch.setPolicyNo(frozen.policy().policyNo());
                    batch.setFulfillment("DELIVERY");
                    batch.setPayMode("CHANNEL");
                    batch.setScheduledAt(
                            frozen.original().scheduledAt() == null
                                    ? null
                                    : frozen.original()
                                            .scheduledAt()
                                            .atOffset(ZoneOffset.UTC)
                                            .toLocalDateTime());
                    batch.setQuotaClaimRefs(json.writeValueAsString(frozen.quotaClaims()));
                    batches.insert(batch);
                    require(
                            sources.update(
                                            Wrappers.<FnbChannelOrderSource>update()
                                                    .eq("tenant_no", "MAIN")
                                                    .eq("id", source.getId())
                                                    .eq("version", source.getVersion())
                                                    .set("core_order_no", preparation.orderNo())
                                                    .set(
                                                            "core_sub_order_no",
                                                            preparation.subOrderNo())
                                                    .set("status", "PREPARED")
                                                    .set(
                                                            "core_preparation_snapshot",
                                                            json.writeValueAsString(preparation))
                                                    .setSql("version=version+1"))
                                    == 1);
                    return batch;
                });
    }

    record Claim(FnbChannelOrderSource source, long fence) {}

    Claim claim(Key key, String phase) {
        return atomic(
                () -> {
                    if ("ACCEPTING".equals(phase)) activeGuard(key);
                    else {
                        var guard = guard(key, true);
                        require(guard != null && "CANCELLING".equals(guard.getStatus()));
                    }
                    var source = required(key, true);
                    require(
                            !"CANCELLED".equals(source.getStatus())
                                    && !("ACCEPTING".equals(phase)
                                            && List.of("CANCELLING", "RECOVERY_REQUIRED")
                                                    .contains(source.getStatus()))
                                    && source.getAcceptanceReceiptRef() == null
                                    && (source.getLeaseUntil() == null
                                            || !source.getLeaseUntil().isAfter(now())
                                            || "CANCELLING".equals(phase)
                                                    && "ACCEPTING".equals(source.getStatus())));
                    long fence = Math.addExact(source.getFence(), 1);
                    require(
                            sources.update(
                                            Wrappers.<FnbChannelOrderSource>update()
                                                    .eq("tenant_no", "MAIN")
                                                    .eq("id", source.getId())
                                                    .eq("version", source.getVersion())
                                                    .set("status", phase)
                                                    .set("fence", fence)
                                                    .set("lease_until", now().plusMinutes(2))
                                                    .set("next_recovery_at", now().plusMinutes(2))
                                                    .setSql("version=version+1"))
                                    == 1);
                    return new Claim(required(key, false), fence);
                });
    }

    CancellationResult finishCancelled(Key key, Claim claim, Cancellation proof) {
        return atomic(
                () -> {
                    var guard = guard(key, true);
                    require(guard != null && "CANCELLING".equals(guard.getStatus()));
                    var source = required(key, true);
                    require(
                            reference(source).equals(proof.reference())
                                    && source.getFence() == claim.fence()
                                    && "CANCELLING".equals(source.getStatus())
                                    && source.getAcceptanceReceiptRef() == null);
                    quotas.release(key.entityNo(), key.storeNo(), source.getBatchNo());
                    if (frozen(source).original().scheduledAt() != null) {
                        require(slots != null);
                        slots.releaseCart(
                                key.entityNo(), key.storeNo(), "SYSTEM", source.getCartNo());
                    }
                    require(
                            carts.update(
                                            Wrappers.<FnbCheckCart>update()
                                                    .eq("tenant_no", "MAIN")
                                                    .eq("entity_no", key.entityNo())
                                                    .eq("store_no", key.storeNo())
                                                    .eq("cart_no", source.getCartNo())
                                                    .eq("status", "LOCKED")
                                                    .set("status", "CLOSED")
                                                    .setSql("version=version+1"))
                                    == 1);
                    batches.update(
                            Wrappers.<FnbOrderBatch>update()
                                    .eq("tenant_no", "MAIN")
                                    .eq("entity_no", key.entityNo())
                                    .eq("store_no", key.storeNo())
                                    .eq("batch_no", source.getBatchNo())
                                    .eq("status", "PREPARED")
                                    .set("status", "CANCELLED")
                                    .setSql("version=version+1"));
                    String receipt = BizKey.next("FCX");
                    require(
                            guards.update(
                                            Wrappers.<FnbChannelImportGuard>update()
                                                    .eq("tenant_no", "MAIN")
                                                    .eq("id", guard.getId())
                                                    .eq("version", guard.getVersion())
                                                    .set("status", "CANCELLED")
                                                    .set("cancellation_receipt_ref", receipt)
                                                    .set(
                                                            "core_cancellation_snapshot",
                                                            json.writeValueAsString(proof))
                                                    .setSql("version=version+1"))
                                    == 1);
                    require(
                            sources.update(
                                            Wrappers.<FnbChannelOrderSource>update()
                                                    .eq("tenant_no", "MAIN")
                                                    .eq("id", source.getId())
                                                    .eq("version", source.getVersion())
                                                    .set("status", "CANCELLED")
                                                    .set("cancellation_receipt_ref", receipt)
                                                    .set("lease_until", null)
                                                    .set("next_recovery_at", null)
                                                    .setSql("version=version+1"))
                                    == 1);
                    return new CancellationResult(key, receipt, proof);
                });
    }

    void needsAfterSale(Key key, Claim claim, Preparation prepared, Acceptance accepted) {
        atomic(
                () -> {
                    var guard = guard(key, true);
                    require(guard != null && "CANCELLING".equals(guard.getStatus()));
                    var source = required(key, true);
                    require(
                            source.getFence() == claim.fence()
                                    && "CANCELLING".equals(source.getStatus())
                                    && reference(source).equals(prepared.reference())
                                    && reference(source).equals(accepted.reference())
                                    && prepared.status() == Status.ACCEPTED);
                    require(
                            sources.update(
                                            Wrappers.<FnbChannelOrderSource>update()
                                                    .eq("tenant_no", "MAIN")
                                                    .eq("id", source.getId())
                                                    .eq("version", source.getVersion())
                                                    .set("status", "RECOVERY_REQUIRED")
                                                    .set("lease_until", null)
                                                    .set("last_error_code", "AFTER_SALE_REQUIRED")
                                                    .set("next_recovery_at", null)
                                                    .set(
                                                            "core_preparation_snapshot",
                                                            json.writeValueAsString(prepared))
                                                    .set(
                                                            "core_acceptance_snapshot",
                                                            json.writeValueAsString(accepted))
                                                    .set(
                                                            "core_acceptance_receipt_ref",
                                                            accepted.acceptanceReceiptRef())
                                                    .setSql("version=version+1"))
                                    == 1);
                    require(
                            guards.update(
                                            Wrappers.<FnbChannelImportGuard>update()
                                                    .eq("tenant_no", "MAIN")
                                                    .eq("id", guard.getId())
                                                    .eq("version", guard.getVersion())
                                                    .set("status", "AFTER_SALE")
                                                    .setSql("version=version+1"))
                                    == 1);
                    return null;
                });
    }

    void recovery(Key key, Claim claim, String code) {
        atomic(
                () -> {
                    sources.update(
                            Wrappers.<FnbChannelOrderSource>update()
                                    .eq("tenant_no", "MAIN")
                                    .eq("entity_no", key.entityNo())
                                    .eq("store_no", key.storeNo())
                                    .eq("source_no", claim.source().getSourceNo())
                                    .eq("fence", claim.fence())
                                    .set("last_error_code", code)
                                    .set("next_recovery_at", now().plusMinutes(2))
                                    .setSql("version=version+1"));
                    return null;
                });
    }

    static void require(boolean value) {
        if (!value) throw BizException.of(ErrorCode.CONFLICT);
    }
}
