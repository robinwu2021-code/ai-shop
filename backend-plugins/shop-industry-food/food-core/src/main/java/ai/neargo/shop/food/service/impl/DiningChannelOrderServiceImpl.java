package ai.neargo.shop.food.service.impl;

import static ai.neargo.shop.food.service.impl.DiningChannelSourceStore.require;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.DiningChannelSourceFacts;
import ai.neargo.shop.food.dto.DiningChannelSourceFacts.Key;
import ai.neargo.shop.food.dto.GetPolicyQuery;
import ai.neargo.shop.food.dto.KitchenContracts.FrozenRoutes;
import ai.neargo.shop.food.entity.FnbChannelOrderSource;
import ai.neargo.shop.food.entity.FnbOrderBatch;
import ai.neargo.shop.food.service.DiningChannelOrderService;
import ai.neargo.shop.food.service.KitchenTaskService;
import ai.neargo.shop.food.service.StoreOperationService;
import ai.neargo.shop.food.spi.DiningChannelSourceFactsPort;
import ai.neargo.shop.food.spi.DiningChannelSourceFactsPort.CurrentEvidence;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort.Plan;
import ai.neargo.shop.spi.trade.IndustryChannelOrderFacts;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort.Acceptance;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort.Preparation;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort.Reference;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort.Status;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort.CollectionFacts;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort.Facts;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort.PlatformAcceptance;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort.Withdrawal;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import java.util.List;
import java.util.function.Supplier;

/**
 * Original restaurant admission coordinates owned source/quota data with actual core receipts;
 * there is no payment shim.
 */
public final class DiningChannelOrderServiceImpl implements DiningChannelOrderService {
    private final DiningChannelSourceStore store;
    private final IndustryChannelOrderPort core;
    private final Supplier<List<DiningChannelSourceFactsPort>> facts;
    private final StoreOperationService policies;
    private final KitchenTaskService kitchen;
    private final DiningChannelProductionPlanBuilder planner;
    private final DiningChannelOrderBinder binder;

    public DiningChannelOrderServiceImpl(
            DiningChannelSourceStore store,
            IndustryChannelOrderPort core,
            Supplier<List<DiningChannelSourceFactsPort>> facts,
            StoreOperationService policies,
            KitchenTaskService kitchen,
            DiningChannelProductionPlanBuilder planner,
            DiningChannelOrderBinder binder) {
        this.store = store;
        this.core = core;
        this.facts = facts;
        this.policies = policies;
        this.kitchen = kitchen;
        this.planner = planner;
        this.binder = binder;
    }

    @Override
    public Binding binding(Key key) {
        key(key);
        return store.query(
                () -> {
                    var guard = store.guard(key, false);
                    var source = store.find(key, false);
                    if (source == null && guard != null && "CANCELLED".equals(guard.getStatus()))
                        return new Binding(
                                key,
                                null,
                                null,
                                null,
                                "CANCELLED",
                                null,
                                guard.getCancellationReceiptRef(),
                                null,
                                null);
                    if (source == null) throw BizException.of(ErrorCode.NOT_FOUND);
                    boolean hasBatch =
                            store.batches.selectCount(
                                            Wrappers.<FnbOrderBatch>query()
                                                    .eq("tenant_no", "MAIN")
                                                    .eq("entity_no", key.entityNo())
                                                    .eq("store_no", key.storeNo())
                                                    .eq("batch_no", source.getBatchNo()))
                                    > 0;
                    String status =
                            guard != null && !"ACTIVE".equals(guard.getStatus())
                                    ? guard.getStatus()
                                    : source.getStatus();
                    return new Binding(
                            key,
                            store.reference(source),
                            source.getCartNo(),
                            hasBatch ? source.getBatchNo() : null,
                            status,
                            source.getAcceptanceReceiptRef(),
                            source.getCancellationReceiptRef(),
                            source.getCoreOrderNo(),
                            source.getCoreSubOrderNo());
                });
    }

    @Override
    public Result prepare(Key key) {
        key(key);
        var guard = store.query(() -> store.guard(key, false));
        if (guard != null && "CANCELLED".equals(guard.getStatus()))
            return new Result(binding(key), null);
        var source = store.query(() -> store.find(key, false));
        if (source == null) {
            var evidence = owner().read(key);
            require(evidence != null && key.equals(evidence.key()) && evidence.original() != null);
            IndustryChannelOrderFacts.freshness(
                    evidence.observedAt(), evidence.validUntil(), store.clock);
            var policy =
                    policies.getPolicy(
                            key.entityNo(),
                            key.storeNo(),
                            new GetPolicyQuery(evidence.original().orderedAt()));
            var routes =
                    kitchen.freezeRoutes(
                            key.entityNo(),
                            key.storeNo(),
                            evidence.original().lines().stream()
                                    .flatMap(line -> line.components().stream())
                                    .map(DiningChannelSourceFacts.Component::skuNo)
                                    .distinct()
                                    .toList());
            validateProductionRoutes(key, evidence.original(), routes);
            source = store.register(evidence, policy, routes);
        }
        var preparation = reconcilePreparation(key, source);
        return new Result(binding(key), preparation);
    }

    @Override
    public Result get(Key key) {
        key(key);
        var binding = binding(key);
        if (binding.reference() == null && "CANCELLED".equals(binding.status()))
            return new Result(binding, null);
        return new Result(
                binding, reconcilePreparation(key, store.query(() -> store.required(key, false))));
    }

    private Preparation reconcilePreparation(Key key, FnbChannelOrderSource source) {
        if (source.getCoreAcceptanceReceiptRef() != null) return storedPreparation(source);
        if ("CANCELLED".equals(source.getStatus())) {
            var cancelled =
                    source.getCorePreparationSnapshot() == null ? null : storedPreparation(source);
            return new Preparation(
                    store.reference(source),
                    Status.CANCELLED,
                    cancelled == null ? source.getCoreOrderNo() : cancelled.orderNo(),
                    cancelled == null ? source.getCoreSubOrderNo() : cancelled.subOrderNo(),
                    null,
                    null,
                    "RELEASED",
                    null,
                    cancelled == null ? null : cancelled.quoteNo());
        }
        positive(source);
        var reference = store.reference(source);
        var preparation = core.get(reference);
        if (preparation == null) preparation = core.prepare(reference);
        require(preparation != null && reference.equals(preparation.reference()));
        if (preparation.status() != Status.CANCELLED && preparation.status() != Status.CANCELLING)
            store.recordPreparation(key, preparation);
        return preparation;
    }

    @Override
    public Acceptance confirm(Key key) {
        key(key);
        var source = store.query(() -> store.required(key, false));
        if (source.getAcceptanceReceiptRef() != null) return storedAcceptance(source);
        var prepared = reconcilePreparation(key, source);
        require(
                prepared.status() == Status.PREPARED
                        || prepared.status() == Status.ACCEPTED
                        || prepared.status() == Status.CONFIRMING);
        var claim = store.claim(key, "ACCEPTING");
        try {
            var accepted = core.confirm(store.reference(claim.source()));
            var actual = core.get(accepted.reference());
            require(actual != null && actual.status() == Status.ACCEPTED);
            binder.bind(key, claim.fence(), actual, accepted);
            return accepted;
        } catch (RuntimeException unavailable) {
            store.recovery(key, claim, "CORE_ACCEPTANCE_RECONCILIATION_REQUIRED");
            throw unavailable;
        }
    }

    @Override
    public CancellationResult cancel(Key key) {
        key(key);
        var guard = store.query(() -> store.guard(key, false));
        if (guard != null && "CANCELLED".equals(guard.getStatus()))
            return store.cancelled(key, guard);
        var current = owner().current(key);
        require(current != null && key.equals(current.key()) && current.current() != null);
        IndustryChannelOrderFacts.freshness(
                current.observedAt(), current.validUntil(), store.clock);
        require(current.current().state() == DiningChannelSourceFacts.State.CANCELLED);
        IndustryChannelOrderFacts.key(current.current().originalReadReceiptRef(), 256);
        var absent = store.beginCancellation(key, current);
        if (absent != null) return absent;
        var claim = store.claim(key, "CANCELLING");
        try {
            var proof = core.cancel(store.reference(claim.source()));
            return store.finishCancelled(key, claim, proof);
        } catch (RuntimeException unavailable) {
            // A real committed trade is an after-sale case, never a successful prepared
            // cancellation.
            try {
                var prepared = core.get(store.reference(claim.source()));
                if (prepared != null && prepared.status() == Status.ACCEPTED) {
                    var accepted =
                            core.confirm(
                                    prepared.reference()); // completed original receipt replay only
                    store.needsAfterSale(key, claim, prepared, accepted);
                    throw BizException.of(ErrorCode.CONFLICT);
                }
            } catch (BizException pending) {
                if (pending.errorCode() == ErrorCode.CONFLICT) throw pending;
            } catch (RuntimeException pending) {
                /* Preserve original cancellation lease and query recovery. */
            }
            store.recovery(key, claim, "CHANNEL_WITHDRAWAL_RECONCILIATION_REQUIRED");
            throw unavailable;
        }
    }

    @Override
    public Facts sourceFacts(Reference reference) {
        var source = source(reference);
        positive(source);
        var current = owner().current(key(source));
        validateCurrent(source, current, true);
        return new Facts(
                reference,
                store.query(() -> store.data(source)),
                current.observedAt(),
                current.validUntil(),
                current.sourceReceiptRef());
    }

    @Override
    public CollectionFacts collection(Reference reference) {
        var source = source(reference);
        positive(source);
        var evidence = owner().current(key(source));
        validateCurrent(source, evidence, true);
        var data = store.query(() -> store.data(source));
        var current = evidence.current();
        return new CollectionFacts(
                reference,
                current.externalOrderRef(),
                current.documentHash(),
                current.financialHash(),
                current.collection(),
                current.collectedMinor(),
                data.currency(),
                current.originalCollectionRef(),
                evidence.observedAt(),
                evidence.validUntil(),
                evidence.sourceReceiptRef());
    }

    @Override
    public PlatformAcceptance platformAcceptance(Reference reference) {
        var source = source(reference);
        positive(source);
        var evidence = owner().current(key(source));
        validateCurrent(source, evidence, true);
        var current = evidence.current();
        require(current.state() == DiningChannelSourceFacts.State.ACCEPTED);
        return new PlatformAcceptance(
                reference,
                current.externalOrderRef(),
                current.documentHash(),
                current.financialHash(),
                current.originalReadReceiptRef(),
                current.originalAcceptedAt(),
                evidence.observedAt(),
                evidence.validUntil(),
                evidence.sourceReceiptRef());
    }

    @Override
    public Withdrawal withdrawal(Reference reference) {
        var source = source(reference);
        var evidence = owner().current(key(source));
        validateCurrent(source, evidence, false);
        var current = evidence.current();
        require(current.state() == DiningChannelSourceFacts.State.CANCELLED);
        return new Withdrawal(
                reference,
                "PLATFORM_CANCELLED",
                current.originalReadReceiptRef(),
                evidence.observedAt(),
                evidence.validUntil(),
                evidence.sourceReceiptRef());
    }

    @Override
    public Plan demandPlan(Reference reference, String reservationRef, String subOrderNo) {
        var source = source(reference);
        positive(source);
        return planner.build(source, reservationRef, subOrderNo);
    }

    private FnbChannelOrderSource source(Reference reference) {
        IndustryChannelOrderFacts.reference(reference);
        require("FOOD".equals(reference.sourceSystem()));
        return store.query(
                () -> {
                    var source =
                            store.sources.selectOne(
                                    Wrappers.<FnbChannelOrderSource>query()
                                            .eq("tenant_no", "MAIN")
                                            .eq("entity_no", reference.scope().entityNo())
                                            .eq("store_no", reference.scope().storeNo())
                                            .eq("source_no", reference.sourceNo())
                                            .eq("import_no", reference.importRef()));
                    if (source == null) throw BizException.of(ErrorCode.NOT_FOUND);
                    require(store.reference(source).equals(reference));
                    return source;
                });
    }

    private void validateCurrent(
            FnbChannelOrderSource source, CurrentEvidence evidence, boolean positive) {
        require(
                evidence != null
                        && key(source).equals(evidence.key())
                        && evidence.current() != null);
        IndustryChannelOrderFacts.freshness(
                evidence.observedAt(), evidence.validUntil(), store.clock);
        IndustryChannelOrderFacts.key(evidence.sourceReceiptRef(), 256);
        var current = evidence.current();
        require(
                source.getExternalOrderRef().equals(current.externalOrderRef())
                        && IndustryChannelOrderFacts.hash(current.documentHash())
                        && IndustryChannelOrderFacts.hash(current.financialHash())
                        && current.state() != null);
        IndustryChannelOrderFacts.key(current.originalReadReceiptRef(), 256);
        if (positive) {
            var latest = store.query(() -> store.required(key(source), false));
            positive(latest);
        }
        if (positive)
            require(
                    source.getOriginalFinancialHash().equals(current.financialHash())
                            && current.state() != DiningChannelSourceFacts.State.CANCELLED
                            && current.state() != DiningChannelSourceFacts.State.COMPLETED);
    }

    private void positive(FnbChannelOrderSource source) {
        var guard = store.query(() -> store.guard(key(source), false));
        require(guard != null && "ACTIVE".equals(guard.getStatus()));
        require(
                !List.of("CANCELLING", "CANCELLED", "RECOVERY_REQUIRED")
                        .contains(source.getStatus()));
    }

    private Preparation storedPreparation(FnbChannelOrderSource source) {
        require(source.getCorePreparationSnapshot() != null);
        var result = store.json.readValue(source.getCorePreparationSnapshot(), Preparation.class);
        require(store.reference(source).equals(result.reference()));
        return result;
    }

    private Acceptance storedAcceptance(FnbChannelOrderSource source) {
        require(
                source.getCoreAcceptanceSnapshot() != null
                        && source.getCoreAcceptanceReceiptRef() != null);
        var result = store.json.readValue(source.getCoreAcceptanceSnapshot(), Acceptance.class);
        require(
                store.reference(source).equals(result.reference())
                        && source.getCoreAcceptanceReceiptRef()
                                .equals(result.acceptanceReceiptRef()));
        return result;
    }

    private void validateProductionRoutes(
            Key key, DiningChannelSourceFacts.Original original, FrozenRoutes routes) {
        for (var line : original.lines()) {
            boolean modifierMaterials = false;
            var modifierSelections =
                    line.selections().stream()
                            .filter(s -> "MODIFIER".equals(s.sourceKind()))
                            .toList();
            var resolver =
                    modifierSelections.isEmpty()
                            ? null
                            : new DiningChannelOriginalModifierResolver(
                                    key.entityNo(),
                                    key.storeNo(),
                                    line.menuNo(),
                                    line.menuItemNo(),
                                    line.menuRevisionNo(),
                                    original.definitions(),
                                    store.json);
            for (var selection : modifierSelections) {
                if (!"MODIFIER".equals(selection.sourceKind())) continue;
                var body = resolver.body(selection);
                modifierMaterials |=
                        body.modifier().data().salesSkuNo() == null
                                && !body.modifier().data().components().isEmpty();
            }
            for (var component : line.components()) {
                if (component.recipeRevisionNo() == null
                        && !("MAIN".equals(component.externalKind()) && modifierMaterials))
                    continue;
                require(
                        routes != null
                                && routes.routes().stream()
                                        .anyMatch(
                                                route ->
                                                        "MAKE".equals(route.taskRole())
                                                                && component
                                                                        .skuNo()
                                                                        .equals(route.skuNo())));
            }
        }
    }

    private DiningChannelSourceFactsPort owner() {
        var available = facts.get();
        if (available.size() != 1) throw BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED);
        return available.getFirst();
    }

    private static Key key(FnbChannelOrderSource source) {
        return new Key(
                source.getEntityNo(),
                source.getStoreNo(),
                source.getImportNo(),
                source.getOriginalDocumentHash());
    }

    private static void key(Key key) {
        require(key != null);
        IndustryChannelOrderFacts.key(key.entityNo(), 32);
        IndustryChannelOrderFacts.key(key.storeNo(), 32);
        IndustryChannelOrderFacts.key(key.importNo(), 32);
        require(IndustryChannelOrderFacts.hash(key.originalDocumentHash()));
    }
}
