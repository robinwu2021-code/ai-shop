package ai.neargo.shop.trade.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.MerchantTaxSnapshot;
import ai.neargo.shop.common.pricing.MoneyMinor;
import ai.neargo.shop.common.pricing.PriceTaxMode;
import ai.neargo.shop.common.pricing.PricingFeeAllocation;
import ai.neargo.shop.common.pricing.PricingPortion;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.common.pricing.PricingUnit;
import ai.neargo.shop.common.pricing.TaxEngine;
import ai.neargo.shop.common.pricing.TaxLineResult;
import ai.neargo.shop.common.promotion.CouponCalculation;
import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanFacts;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort;
import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import ai.neargo.shop.spi.trade.IndustryAmendmentSourcePort;
import ai.neargo.shop.spi.trade.IndustryFulfillmentPort.Reference;
import ai.neargo.shop.spi.trade.IndustryOrderAmendmentPort.Command;
import ai.neargo.shop.spi.trade.IndustryOrderAmendmentPort.FeeScopeAdjustment;
import ai.neargo.shop.spi.trade.IndustryOrderAmendmentPort.Line;
import ai.neargo.shop.spi.trade.IndustryOrderAmendmentPort.Receipt;
import ai.neargo.shop.spi.trade.IndustryOrderAmendmentPort.Reduction;
import ai.neargo.shop.spi.trade.IndustryOrderPort;
import ai.neargo.shop.spi.trade.OrderEvents;
import ai.neargo.shop.spi.trade.PricingFeeFacts;
import ai.neargo.shop.spi.trade.PricingFeeScopePort;
import ai.neargo.shop.spi.user.MerchantScopeFactsPort;
import ai.neargo.shop.trade.entity.OrdIndustryOrderSource;
import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.mapper.IndustryOrderAmendmentMapper;
import ai.neargo.shop.trade.mapper.IndustryOrderAmendmentRows;
import ai.neargo.shop.trade.mapper.IndustryOrderAmendmentRows.Journal;
import ai.neargo.shop.trade.mapper.IndustryOrderAmendmentRows.ReductionColumn;
import ai.neargo.shop.trade.mapper.TradeMappers.IndustryOrderSourceMapper;
import ai.neargo.shop.trade.order.machine.order.OrderContext;
import ai.neargo.shop.trade.order.machine.order.OrderEvent;
import ai.neargo.shop.trade.order.mapper.OrderItemMapper;
import ai.neargo.shop.trade.order.mapper.PaymentMapper;
import ai.neargo.shop.trade.order.mapper.StoreOrderMapper;
import ai.neargo.shop.trade.order.statemachine.Actor;
import ai.neargo.shop.trade.order.transition.TradeStatusWriter;
import ai.neargo.shop.trade.service.IndustryOrderAmendmentService;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Append-only unpaid debt corrections; inventory release and source evidence retain their original
 * owners.
 */
@Service
public class IndustryOrderAmendmentServiceImpl implements IndustryOrderAmendmentService {
    private final IndustryOrderAmendmentMapper persistence;
    private final TradeStatusWriter writer;
    private final OutboxEventBus events;
    private final TransactionTemplate tx;
    private final TransactionTemplate projectionRead;
    private final ObjectMapper json;
    private final IndustryOrderSourceMapper sources;
    private final PaymentMapper orders;
    private final StoreOrderMapper subs;
    private final OrderItemMapper items;
    private final MerchantScopeFactsPort merchants;
    private final ObjectProvider<InventoryDemandPlanPort> inventory;
    private final ObjectProvider<IndustryAmendmentSourcePort> evidence;
    private final ObjectProvider<PricingFeeScopePort> fees;

    private record Facts(
            OrdIndustryOrderSource source, OrdOrder order, OrdSubOrder sub, List<OrdItem> items) {}

    public IndustryOrderAmendmentServiceImpl(
            IndustryOrderAmendmentMapper persistence,
            TradeStatusWriter writer,
            OutboxEventBus events,
            @Qualifier("transactionManager") PlatformTransactionManager manager,
            ObjectMapper json,
            IndustryOrderSourceMapper sources,
            PaymentMapper orders,
            StoreOrderMapper subs,
            OrderItemMapper items,
            MerchantScopeFactsPort merchants,
            ObjectProvider<InventoryDemandPlanPort> inventory,
            ObjectProvider<IndustryAmendmentSourcePort> evidence,
            ObjectProvider<PricingFeeScopePort> fees) {
        this.persistence = persistence;
        this.writer = writer;
        this.events = events;
        tx = new TransactionTemplate(manager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        projectionRead = new TransactionTemplate(manager);
        projectionRead.setReadOnly(true);
        projectionRead.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.json = json;
        this.sources = sources;
        this.orders = orders;
        this.subs = subs;
        this.items = items;
        this.merchants = merchants;
        this.inventory = inventory;
        this.evidence = evidence;
        this.fees = fees;
    }

    @Override
    public Receipt amend(Command c, String key) {
        outside();
        if (c == null
                || c.lines().isEmpty()
                || c.lines().size() > 1000
                || c.expectedSubVersion() < 0
                || c.expectedSubVersion() == Long.MAX_VALUE
                || !MaterialCatalogFacts.validKey(c.sourceReturnNo(), 32)
                || c.sourceHash() == null
                || !c.sourceHash().matches("[0-9a-f]{64}")
                || c.reason() == null
                || c.reason().isBlank()
                || c.reason().length() > 255) throw bad();
        authorize(c.reference(), c.actorNo(), key);
        var ref = c.reference();
        String operation = operation(ref, key), hash = PricingSnapshotHash.of(c);
        var known = sql(() -> byKey(ref, operation));
        if (known != null) {
            same(known, hash);
            if ("CONFIRMED".equals(known.status())) return sql(() -> project(known, ref));
            return execute(known, c);
        }
        var source = provider(evidence).get(ref, c.sourceReturnNo());
        if (source == null
                || !ref.equals(source.reference())
                || !c.sourceReturnNo().equals(source.returnNo())
                || !c.sourceHash().equals(source.sourceHash())
                || !c.actorNo().equals(source.actorNo())
                || !"HANDLED".equals(source.kitchenStatus())
                || !canonical(c.lines()).equals(canonical(source.lines()))
                || !Objects.equals(c.replacementCartNo(), source.replacementCartNo()))
            throw conflict();
        var prepared =
                write(
                        () -> {
                            var f = facts(ref, true);
                            eligible(f, c.expectedSubVersion());
                            var prior = byKey(ref, operation);
                            if (prior != null) {
                                same(prior, hash);
                                return prior;
                            }
                            exclusive(f);
                            var plan = plan(f);
                            var allocations = allocations(f);
                            var originalFees = originalFeeAllocations(f, ref, allocations);
                            var deltas = new ArrayList<Calculated>();
                            long amount = 0;
                            for (var r : c.lines()) {
                                var original =
                                        f.items().stream()
                                                .filter(
                                                        i ->
                                                                Objects.equals(
                                                                        i.getId(), r.orderItemId()))
                                                .findFirst()
                                                .orElseThrow(
                                                        IndustryOrderAmendmentServiceImpl
                                                                ::conflict);
                                var allocation =
                                        allocations.stream()
                                                .filter(
                                                        a ->
                                                                a.orderItemId() == r.orderItemId()
                                                                        && a.componentRef()
                                                                                .equals(
                                                                                        r
                                                                                                .sourceComponentRef()))
                                                .findFirst()
                                                .orElseThrow(
                                                        IndustryOrderAmendmentServiceImpl
                                                                ::conflict);
                                if (!Objects.equals(
                                                original.getSourceComponentRef(),
                                                r.sourceComponentRef())
                                        || original.getSaleQuantity() == null
                                        || !Objects.equals(original.getQuantityUnit(), r.unit())
                                        || !Objects.equals(allocation.quantityUnit(), r.unit())
                                        || allocation
                                                        .saleQuantity()
                                                        .compareTo(original.getSaleQuantity())
                                                != 0) throw conflict();
                                BigDecimal quantity;
                                try {
                                    quantity =
                                            InventoryDemandPlanFacts.quantity(r.quantity(), false);
                                } catch (IllegalArgumentException invalid) {
                                    throw bad();
                                }
                                if (!Set.of("CANCEL", "LOSS", "KEEP", "COMP", "GIFT")
                                        .contains(r.disposition())) throw bad();
                                var previous =
                                        reduced(ref, original.getId(), ReductionColumn.QUANTITY);
                                var cumulative = previous.add(quantity);
                                if (cumulative.compareTo(original.getSaleQuantity()) > 0)
                                    throw conflict();
                                BigDecimal handed =
                                        persistence.fulfilled(
                                                ref, original.getId(), r.sourceComponentRef());
                                var outstanding =
                                        original.getSaleQuantity()
                                                .subtract(
                                                        reduced(
                                                                ref,
                                                                original.getId(),
                                                                ReductionColumn.PHYSICAL))
                                                .subtract(handed);
                                if (outstanding.signum() < 0) throw conflict();
                                var physical =
                                        Set.of("KEEP", "COMP", "GIFT").contains(r.disposition())
                                                ? BigDecimal.ZERO
                                                : "LOSS".equals(r.disposition())
                                                        ? quantity.min(outstanding)
                                                        : quantity;
                                if (physical.compareTo(outstanding) > 0) throw conflict();
                                long priorMoney = moneyReduced(ref, original.getId());
                                long goodsBase =
                                        Math.subtractExact(
                                                allocation.amountMinor(),
                                                originalFees
                                                        .getOrDefault(
                                                                allocation.orderItemId(),
                                                                new MoneyBasis(0, 0))
                                                        .gross());
                                if (goodsBase < 0) throw conflict();
                                long entitlement =
                                        BigDecimal.valueOf(goodsBase)
                                                .multiply(cumulative)
                                                .divide(
                                                        original.getSaleQuantity(),
                                                        0,
                                                        RoundingMode.FLOOR)
                                                .longValueExact();
                                long delta = Math.subtractExact(entitlement, priorMoney);
                                if (delta < 0) throw conflict();
                                amount = Math.addExact(amount, delta);
                                var goodsMoney =
                                        originalGoodsMoney(
                                                f,
                                                ref,
                                                allocation,
                                                originalFees.getOrDefault(
                                                        allocation.orderItemId(),
                                                        new MoneyBasis(0, 0)));
                                if (goodsMoney.gross() != goodsBase) throw conflict();
                                long netEntitlement =
                                        goodsBase == 0
                                                ? 0
                                                : BigDecimal.valueOf(goodsMoney.net())
                                                        .multiply(BigDecimal.valueOf(entitlement))
                                                        .divide(
                                                                BigDecimal.valueOf(goodsBase),
                                                                0,
                                                                RoundingMode.FLOOR)
                                                        .longValueExact();
                                long netDelta =
                                        Math.subtractExact(
                                                netEntitlement,
                                                persistence.netReduced(ref, original.getId()));
                                if (netDelta < 0 || netDelta > delta) throw conflict();
                                deltas.add(
                                        new Calculated(
                                                r,
                                                physical,
                                                delta,
                                                netDelta,
                                                Math.subtractExact(delta, netDelta)));
                            }
                            var no = BizKey.next("IAMD");
                            long debtDelta = Math.negateExact(amount);
                            if (Math.addExact(
                                            effectiveDebt(ref, f.order().getPayAmount()), debtDelta)
                                    < 0) throw conflict();
                            persistence.insert(
                                    new IndustryOrderAmendmentRows.ReturnDraft(
                                            ref.scope().tenantNo(),
                                            ref.scope().entityNo(),
                                            ref.scope().storeNo(),
                                            ref.orderNo(),
                                            ref.subOrderNo(),
                                            no,
                                            operation,
                                            hash,
                                            "RETURN",
                                            c.sourceReturnNo(),
                                            c.sourceHash(),
                                            json.writeValueAsString(c),
                                            json.writeValueAsString(source),
                                            "PREPARED",
                                            c.actorNo(),
                                            c.expectedSubVersion(),
                                            f.order().getCurrency(),
                                            debtDelta,
                                            c.replacementCartNo()));
                            for (var d : deltas)
                                persistence.insertLine(
                                        no,
                                        new IndustryOrderAmendmentRows.Line(
                                                d.line().orderItemId(),
                                                d.line().sourceComponentRef(),
                                                d.line().quantity(),
                                                d.physical(),
                                                "CANCEL".equals(d.line().disposition())
                                                        ? d.line().quantity()
                                                        : BigDecimal.ZERO,
                                                d.line().unit(),
                                                d.line().disposition(),
                                                d.money(),
                                                d.net(),
                                                d.tax()),
                                        c.actorNo());
                            persistence.returnMoney(
                                    no,
                                    Math.negateExact(
                                            deltas.stream().mapToLong(Calculated::net).sum()),
                                    Math.negateExact(
                                            deltas.stream().mapToLong(Calculated::tax).sum()));
                            return byKey(ref, operation);
                        });
        return execute(prepared, c);
    }

    private record Calculated(
            Reduction line, BigDecimal physical, long money, long net, long tax) {}

    private Receipt execute(Journal journal, Command c) {
        var ref = c.reference();
        try {
            var f = sql(() -> facts(ref, false));
            var p = plan(f);
            var proof = provider(inventory).query(p.scope());
            var targets = new ArrayList<InventoryDemandPlanPort.Target>();
            for (var r : c.lines())
                if ("CANCEL".equals(r.disposition())) {
                    var branches =
                            p.branches().stream()
                                    .filter(
                                            b ->
                                                    b.sourceComponentRef()
                                                            .equals(r.sourceComponentRef()))
                                    .toList();
                    if (branches.isEmpty())
                        continue; // MAIN assembly containers carry no stock leaf.
                    if (branches.size() != 1) throw conflict();
                    var branch = branches.getFirst();
                    var cumulative =
                            sql(() -> reduced(ref, r.orderItemId(), ReductionColumn.CANCEL))
                                    .add(r.quantity());
                    for (var leaf : branch.leaves()) {
                        BigDecimal target;
                        try {
                            target =
                                    leaf.quantity()
                                            .multiply(cumulative)
                                            .divide(branch.quantity(), 6, RoundingMode.UNNECESSARY);
                        } catch (ArithmeticException unsupported) {
                            throw conflict();
                        }
                        targets.add(new InventoryDemandPlanPort.Target(leaf.leafRef(), target));
                    }
                }
            if (!targets.isEmpty())
                proof =
                        provider(inventory)
                                .releaseTo(p.scope(), journal.amendmentNo(), p.planHash(), targets);
            if (!p.scope().equals(proof.scope()) || !p.planHash().equals(proof.planHash()))
                throw conflict();
            for (var target : targets)
                if (proof.lines().stream()
                        .noneMatch(
                                l ->
                                        l.leafRef().equals(target.leafRef())
                                                && l.releasedQty().compareTo(target.cumulativeQty())
                                                        >= 0)) throw conflict();
            final var confirmed = proof;
            return write(
                    () -> {
                        var current = byKey(ref, journal.operationKey());
                        if ("CONFIRMED".equals(current.status())) return project(current, ref);
                        var locked = facts(ref, true);
                        eligible(locked, current.expectedSubVersion());
                        appendDebt(current, ref, c.reason());
                        persistence.confirm(
                                current.id(), json.writeValueAsString(confirmed), c.actorNo());
                        provider(fees)
                                .reconcileOnAmendment(
                                        ref, current.amendmentNo(), current.sourceHash());
                        closeSatisfied(locked, ref, c.actorNo());
                        return project(byKey(ref, current.operationKey()), ref);
                    });
        } catch (RuntimeException unknown) {
            write(
                    () -> {
                        persistence.unknown(journal.id());
                        return null;
                    });
            throw unknown;
        }
    }

    @Override
    public Receipt query(Reference ref, String actor, String key) {
        outside();
        authorize(ref, actor, key);
        var row = sql(() -> byKey(ref, operation(ref, key)));
        if (row == null)
            return sql(
                    () -> {
                        var f = facts(ref, false);
                        return new Receipt(
                                null,
                                ref,
                                "NOT_STARTED",
                                true,
                                f.order().getCurrency(),
                                null,
                                effectiveDebt(ref, f.order().getPayAmount()),
                                f.sub().getVersion(),
                                List.of(),
                                null);
                    });
        sameReference(row, ref);
        if (!actor.equals(row.actorNo())) throw forbidden();
        if (!"CONFIRMED".equals(row.status()) && "RETURN".equals(row.kind()))
            return execute(row, json.readValue(row.commandSnapshot(), Command.class));
        return sql(() -> project(row, ref));
    }

    @Override
    public Receipt adjustFee(FeeScopeAdjustment c, String key) {
        outside();
        if (c == null
                || c.expectedSubVersion() < 0
                || c.targetFeeMinor() < 0
                || c.reason() == null
                || c.reason().isBlank()
                || c.reason().length() > 255) throw bad();
        authorize(c.reference(), c.actorNo(), key);
        var ref = c.reference();
        String operation = operation(ref, key), hash = PricingSnapshotHash.of(c);
        return write(
                () -> {
                    var f = facts(ref, true);
                    var prior = byKey(ref, operation);
                    if (prior != null) {
                        same(prior, hash);
                        return project(prior, ref);
                    }
                    eligible(f, c.expectedSubVersion());
                    exclusive(f);
                    var authority =
                            provider(fees)
                                    .authorizeAdjustment(
                                            ref.scope(),
                                            c.feeApplicationNo(),
                                            c.targetFeeMinor(),
                                            c.sourceHash());
                    if (!ref.orderNo().equals(authority.orderNo())
                            || !ref.subOrderNo().equals(authority.subOrderNo())
                            || !f.order().getCurrency().equals(authority.currency())
                            || !c.sourceHash().equals(authority.sourceHash())) throw conflict();
                    var feeMoney = feeMoney(f, c, authority);
                    long delta = feeMoney.grossDelta();
                    if (Math.addExact(effectiveDebt(ref, f.order().getPayAmount()), delta) < 0)
                        throw conflict();
                    String no = BizKey.next("IAMD");
                    persistence.insert(
                            new IndustryOrderAmendmentRows.ReturnDraft(
                                    ref.scope().tenantNo(),
                                    ref.scope().entityNo(),
                                    ref.scope().storeNo(),
                                    ref.orderNo(),
                                    ref.subOrderNo(),
                                    no,
                                    operation,
                                    hash,
                                    "FEE",
                                    null,
                                    c.sourceHash(),
                                    json.writeValueAsString(c),
                                    json.writeValueAsString(authority),
                                    "CONFIRMED",
                                    c.actorNo(),
                                    c.expectedSubVersion(),
                                    authority.currency(),
                                    delta,
                                    null));
                    persistence.feeMoney(
                            no,
                            feeMoney.netDelta(),
                            feeMoney.taxDelta(),
                            json.writeValueAsString(
                                    new FeeAuthorityEvidence(authority, feeMoney.taxSnapshot())));
                    for (var line : feeMoney.lines())
                        persistence.insertFeeLine(no, line, c.actorNo());
                    var row = byKey(ref, operation);
                    appendDebt(row, ref, c.reason());
                    provider(fees)
                            .completeAdjustment(
                                    ref.scope(),
                                    c.feeApplicationNo(),
                                    c.targetFeeMinor(),
                                    c.sourceHash(),
                                    c.actorNo(),
                                    key);
                    bump(f.sub());
                    return project(row, ref);
                });
    }

    @Override
    public int recover(int limit) {
        outside();
        if (limit < 1 || limit > 200) throw bad();
        var pending = sql(() -> persistence.pending(limit));
        int completed = 0;
        for (var row : pending)
            try {
                var r = execute(row, json.readValue(row.commandSnapshot(), Command.class));
                if (!r.pending()) completed++;
            } catch (RuntimeException unknown) {
                LoggerFactory.getLogger(getClass())
                        .warn(
                                "Unpaid amendment recovery unresolved: {}",
                                row.amendmentNo(),
                                unknown);
            }
        return completed;
    }

    private record FeeAuthorityEvidence(
            PricingFeeScopePort.AdjustmentAuthority authority, String frozenTax) {}

    private record FeeTaxBasis(
            MerchantTaxSnapshot merchantTax,
            String originalInvoice,
            TaxLineResult before,
            TaxLineResult after) {}

    private record FeeMoney(
            long grossDelta,
            long netDelta,
            long taxDelta,
            String taxSnapshot,
            List<IndustryOrderAmendmentRows.FeeLine> lines) {
        public FeeMoney {
            lines = List.copyOf(lines);
        }
    }

    private FeeMoney feeMoney(
            Facts facts,
            FeeScopeAdjustment command,
            PricingFeeScopePort.AdjustmentAuthority authority) {
        var app = persistence.feeApplication(command.reference(), command.feeApplicationNo());
        if (app == null || !facts.source().getQuoteNo().equals(app.quoteNo())) throw conflict();
        var snapshot = persistence.quoteResult(command.reference(), app.quoteNo());
        if (snapshot == null) throw conflict();
        var result = json.readTree(snapshot);
        var tax = json.treeToValue(result.get("tax"), MerchantTaxSnapshot.class);
        var plan = json.treeToValue(result.get("feePlan"), PricingFeeFacts.Plan.class);
        if (plan == null || !PricingSnapshotHash.of(plan.fees()).equals(plan.planHash()))
            throw conflict();
        var selected =
                plan.fees().stream()
                        .filter(
                                f ->
                                        f.rule().ruleNo().equals(app.ruleNo())
                                                && f.rule().revisionNo().equals(app.revisionNo())
                                                && f.source().scopeNo().equals(app.scopeNo()))
                        .toList();
        if (selected.size() != 1 || selected.getFirst().entries().isEmpty()) throw conflict();
        var resolved = selected.getFirst();
        var first = resolved.entries().getFirst();
        if (resolved.entries().stream()
                        .anyMatch(
                                e ->
                                        !e.invoiceRef().equals(first.invoiceRef())
                                                || e.taxClass() != first.taxClass())
                || resolved.entries().stream().mapToLong(PricingFeeFacts.Entry::amountMinor).sum()
                        != app.feeMinor()) throw conflict();
        PricingUnit source = null;
        for (var node : result.get("lines")) {
            var candidate = json.treeToValue(node.get("source"), PricingUnit.class);
            if (PricingPortion.from(candidate).equals(first.portion())) {
                source = candidate;
                break;
            }
        }
        if (source == null) throw conflict();
        var before =
                feeTax(
                        source,
                        first,
                        tax,
                        authority.currency(),
                        authority.currentFeeMinor(),
                        facts.source().getSourceHash(),
                        facts.source().getSourceVersion());
        var after =
                feeTax(
                        source,
                        first,
                        tax,
                        authority.currency(),
                        authority.targetFeeMinor(),
                        facts.source().getSourceHash(),
                        facts.source().getSourceVersion());
        var original = allocations(facts);
        var beforeShares = feeShares(resolved, before, authority.currentFeeMinor());
        var afterShares = feeShares(resolved, after, authority.targetFeeMinor());
        var lineDeltas = new LinkedHashMap<Long, IndustryOrderAmendmentRows.FeeLine>();
        for (int i = 0; i < resolved.entries().size(); i++) {
            var entry = resolved.entries().get(i);
            var allocation =
                    original.stream()
                            .filter(
                                    o ->
                                            o.sourceLineNo().equals(entry.portion().sourceLineNo())
                                                    && o.componentRef()
                                                            .equals(entry.portion().componentRef()))
                            .findFirst()
                            .orElseThrow(IndustryOrderAmendmentServiceImpl::conflict);
            long delta = Math.subtractExact(afterShares.get(i), beforeShares.get(i));
            var previous = lineDeltas.get(allocation.orderItemId());
            lineDeltas.put(
                    allocation.orderItemId(),
                    new IndustryOrderAmendmentRows.FeeLine(
                            allocation.orderItemId(),
                            allocation.componentRef(),
                            Math.addExact(previous == null ? 0 : previous.amountMinor(), delta)));
        }
        long grossDelta = Math.subtractExact(after.gross().minor(), before.gross().minor());
        if (lineDeltas.values().stream()
                        .mapToLong(IndustryOrderAmendmentRows.FeeLine::amountMinor)
                        .sum()
                != grossDelta) throw conflict();
        return new FeeMoney(
                grossDelta,
                Math.subtractExact(after.net().minor(), before.net().minor()),
                Math.subtractExact(after.tax().minor(), before.tax().minor()),
                json.writeValueAsString(new FeeTaxBasis(tax, first.invoiceRef(), before, after)),
                List.copyOf(lineDeltas.values()));
    }

    private TaxLineResult feeTax(
            PricingUnit original,
            PricingFeeFacts.Entry fee,
            MerchantTaxSnapshot tax,
            String currency,
            long amount,
            String hash,
            long version) {
        String component =
                "FEE:"
                        + PricingSnapshotHash.of(
                                        List.of(fee.portion(), fee.ruleNo(), fee.scopeNo()))
                                .substring(0, 32);
        var money = new MoneyMinor(currency, amount);
        var source =
                new PricingUnit(
                        original.sourceLineNo(),
                        component,
                        original.goodsNo(),
                        original.skuNo(),
                        fee.revisionNo(),
                        fee.invoiceRef(),
                        BigDecimal.ZERO,
                        BigDecimal.ONE,
                        money,
                        money);
        return new TaxEngine()
                .estimateConfigured(
                        List.of(
                                new TaxEngine.Line(
                                        source, fee.taxClass(), tax.priceTaxMode(), money)),
                        tax,
                        hash,
                        version)
                .lines()
                .getFirst();
    }

    private List<Long> feeShares(PricingFeeFacts.Resolved fee, TaxLineResult tax, long price) {
        var prices =
                PricingFeeAllocation.allocate(
                        price,
                        fee.entries().stream().map(PricingFeeFacts.Entry::amountMinor).toList());
        if (tax.priceMode() == PriceTaxMode.INCLUSIVE) return prices;
        var taxes = CouponCalculation.allocate(tax.tax().minor(), prices);
        var gross = new ArrayList<Long>();
        for (int i = 0; i < prices.size(); i++)
            gross.add(Math.addExact(prices.get(i), taxes.get(i)));
        return List.copyOf(gross);
    }

    private record MoneyBasis(long gross, long net) {
        MoneyBasis add(MoneyBasis other) {
            return new MoneyBasis(Math.addExact(gross, other.gross), Math.addExact(net, other.net));
        }
    }

    private MoneyBasis originalGoodsMoney(
            Facts facts, Reference ref, IndustryOrderPort.Allocation allocation, MoneyBasis fee) {
        var result = json.readTree(persistence.quoteResult(ref, facts.source().getQuoteNo()));
        long gross = 0, net = 0;
        int matched = 0;
        for (var line : result.path("lines")) {
            var source = json.treeToValue(line.get("source"), PricingUnit.class);
            if (!allocation.sourceLineNo().equals(source.sourceLineNo())
                    || !allocation.componentRef().equals(source.componentRef())) continue;
            var amounts = line.path("amounts");
            if (!amounts.path("receivableMinor").isIntegralNumber()
                    || !amounts.path("netMinor").isIntegralNumber()) throw conflict();
            gross = Math.addExact(gross, amounts.get("receivableMinor").longValue());
            net = Math.addExact(net, amounts.get("netMinor").longValue());
            matched++;
        }
        if (matched == 0 || gross != allocation.amountMinor()) throw conflict();
        var basis =
                new MoneyBasis(
                        Math.subtractExact(gross, fee.gross()), Math.subtractExact(net, fee.net()));
        if (basis.net() < 0 || basis.net() > basis.gross()) throw conflict();
        return basis;
    }

    private Map<Long, MoneyBasis> originalFeeAllocations(
            Facts facts, Reference ref, List<IndustryOrderPort.Allocation> original) {
        var snapshot = persistence.quoteResult(ref, facts.source().getQuoteNo());
        if (snapshot == null) throw conflict();
        var result = json.readTree(snapshot);
        var node = result.get("feePlan");
        var totals = new HashMap<Long, MoneyBasis>();
        if (node == null || node.isNull()) return totals;
        var plan = json.treeToValue(node, PricingFeeFacts.Plan.class);
        if (!PricingSnapshotHash.of(plan.fees()).equals(plan.planHash())) throw conflict();
        var tax = json.treeToValue(result.get("tax"), MerchantTaxSnapshot.class);
        for (var fee : plan.fees()) {
            if (fee.entries().isEmpty()) continue;
            var first = fee.entries().getFirst();
            if (fee.entries().stream()
                    .anyMatch(
                            e ->
                                    !e.invoiceRef().equals(first.invoiceRef())
                                            || e.taxClass() != first.taxClass())) throw conflict();
            PricingUnit source = null;
            for (var line : result.get("lines")) {
                var candidate = json.treeToValue(line.get("source"), PricingUnit.class);
                if (PricingPortion.from(candidate).equals(first.portion())) {
                    source = candidate;
                    break;
                }
            }
            if (source == null) throw conflict();
            long amount =
                    fee.entries().stream().mapToLong(PricingFeeFacts.Entry::amountMinor).sum();
            var invoice =
                    feeTax(
                            source,
                            first,
                            tax,
                            facts.order().getCurrency(),
                            amount,
                            facts.source().getSourceHash(),
                            facts.source().getSourceVersion());
            var shares = feeShares(fee, invoice, amount);
            var taxShares = CouponCalculation.allocate(invoice.tax().minor(), shares);
            for (int i = 0; i < fee.entries().size(); i++) {
                var entry = fee.entries().get(i);
                var allocation =
                        original.stream()
                                .filter(
                                        o ->
                                                o.sourceLineNo()
                                                                .equals(
                                                                        entry.portion()
                                                                                .sourceLineNo())
                                                        && o.componentRef()
                                                                .equals(
                                                                        entry.portion()
                                                                                .componentRef()))
                                .findFirst()
                                .orElseThrow(IndustryOrderAmendmentServiceImpl::conflict);
                totals.merge(
                        allocation.orderItemId(),
                        new MoneyBasis(
                                shares.get(i), Math.subtractExact(shares.get(i), taxShares.get(i))),
                        MoneyBasis::add);
            }
        }
        return totals;
    }

    @Override
    public EffectiveProjection effectiveAllocations(
            Reference ref, List<IndustryOrderPort.Allocation> original) {
        if (ref == null || original == null) throw bad();
        return sql(
                () ->
                        projectionRead.execute(
                                status -> {
                                    var f = facts(ref, false);
                                    var authoritative = allocations(f);
                                    if (!authoritative.equals(original)) throw conflict();
                                    var applied = persistence.applied(ref);
                                    if (applied.isEmpty())
                                        return new EffectiveProjection(
                                                original,
                                                null,
                                                persistence.receivableAdjustment(
                                                        ref.scope().tenantNo(),
                                                        ref.scope().entityNo(),
                                                        ref.orderNo()));
                                    String hash =
                                            PricingSnapshotHash.of(
                                                    List.of(
                                                            "INDUSTRY_EFFECTIVE_ALLOCATIONS_V1",
                                                            ref,
                                                            f.source().getSourceHash(),
                                                            original,
                                                            applied.stream()
                                                                    .map(
                                                                            a ->
                                                                                    List.of(
                                                                                            a
                                                                                                    .amendmentNo(),
                                                                                            a
                                                                                                    .sourceHash(),
                                                                                            a
                                                                                                    .kind(),
                                                                                            a
                                                                                                    .receivableDeltaMinor()))
                                                                    .toList()));
                                    var result = new ArrayList<IndustryOrderPort.Allocation>();
                                    for (var line : original) {
                                        var qty =
                                                line.saleQuantity()
                                                        .subtract(
                                                                persistence.reduced(
                                                                        ref,
                                                                        line.orderItemId(),
                                                                        ReductionColumn.QUANTITY));
                                        long amount =
                                                Math.addExact(
                                                        Math.subtractExact(
                                                                line.amountMinor(),
                                                                persistence.moneyReduced(
                                                                        ref, line.orderItemId())),
                                                        persistence.feeLineAdjustment(
                                                                ref, line.orderItemId()));
                                        if (qty.signum() < 0 || amount < 0) throw conflict();
                                        String allocationHash =
                                                PricingSnapshotHash.of(
                                                        List.of(
                                                                "EFFECTIVE_ALLOCATION_V1",
                                                                line.hash(),
                                                                qty.stripTrailingZeros()
                                                                        .toPlainString(),
                                                                amount,
                                                                hash));
                                        result.add(
                                                new IndustryOrderPort.Allocation(
                                                        line.sourceLineNo(),
                                                        line.componentRef(),
                                                        line.orderItemId(),
                                                        line.qty(),
                                                        amount,
                                                        line.currency(),
                                                        allocationHash,
                                                        qty,
                                                        line.quantityUnit()));
                                    }
                                    long adjustment =
                                            persistence.receivableAdjustment(
                                                    ref.scope().tenantNo(),
                                                    ref.scope().entityNo(),
                                                    ref.orderNo());
                                    long expected =
                                            Math.addExact(
                                                    original.stream()
                                                            .mapToLong(
                                                                    IndustryOrderPort.Allocation
                                                                            ::amountMinor)
                                                            .sum(),
                                                    applied.stream()
                                                            .mapToLong(
                                                                    IndustryOrderAmendmentRows
                                                                                    .Applied
                                                                            ::receivableDeltaMinor)
                                                            .sum());
                                    if (result.stream()
                                                    .mapToLong(
                                                            IndustryOrderPort.Allocation
                                                                    ::amountMinor)
                                                    .sum()
                                            != expected) throw conflict();
                                    return new EffectiveProjection(result, hash, adjustment);
                                }));
    }

    private void appendDebt(Journal row, Reference ref, String reason) {
        persistence.appendDebt(
                new IndustryOrderAmendmentRows.Debt(
                        ref.scope().tenantNo(),
                        ref.scope().entityNo(),
                        ref.scope().storeNo(),
                        ref.orderNo(),
                        ref.subOrderNo(),
                        row.amendmentNo(),
                        row.currency(),
                        row.receivableDeltaMinor(),
                        row.sourceHash(),
                        reason,
                        row.actorNo(),
                        row.netDeltaMinor(),
                        row.taxDeltaMinor()));
    }

    private void closeSatisfied(Facts f, Reference ref, String actor) {
        boolean complete = true;
        boolean hasDelivered = false;
        for (var item : f.items()) {
            var physical = persistence.reduced(ref, item.getId(), ReductionColumn.PHYSICAL);
            var target = item.getSaleQuantity().subtract(physical);
            var delivered = persistence.fulfilled(ref, item.getId(), item.getSourceComponentRef());
            if (target.signum() < 0 || delivered.compareTo(target) > 0) throw conflict();
            if (delivered.compareTo(target) != 0) complete = false;
            if (delivered.signum() > 0) hasDelivered = true;
        }
        if (OrdSubOrder.COMPLETED.equals(f.sub().getStatus())) {
            if (!complete) throw conflict();
            bump(f.sub());
            return;
        }
        if (complete) {
            writer.order(
                    f.sub(),
                    OrderEvent.VERIFIED,
                    Actor.MERCHANT,
                    actor,
                    "原源减量后履约责任已满足",
                    OrderContext.authorizedOnly()
                            .withRequireBuyerConfirm(
                                    hasDelivered
                                            && Boolean.TRUE.equals(
                                                    Integer.valueOf(1)
                                                            .equals(
                                                                    f.sub()
                                                                            .getRequireBuyerConfirm()))));
            if (OrdSubOrder.COMPLETED.equals(f.sub().getStatus()))
                events.publish(
                        new OrderEvents.SubOrderCompleted(
                                ref.subOrderNo(),
                                ref.orderNo(),
                                ref.scope().entityNo(),
                                f.sub().getUserNo(),
                                f.sub().getFulfillment()));
        } else bump(f.sub());
    }

    private void bump(OrdSubOrder sub) {
        if (subs.update(
                        null,
                        Wrappers.<OrdSubOrder>lambdaUpdate()
                                .eq(OrdSubOrder::getId, sub.getId())
                                .eq(OrdSubOrder::getVersion, sub.getVersion())
                                .set(OrdSubOrder::getVersion, sub.getVersion() + 1))
                != 1) throw conflict();
        sub.setVersion(sub.getVersion() + 1);
    }

    private Facts facts(Reference ref, boolean lock) {
        var source =
                sources.selectOne(
                        Wrappers.<OrdIndustryOrderSource>lambdaQuery()
                                .eq(OrdIndustryOrderSource::getTenantNo, ref.scope().tenantNo())
                                .eq(OrdIndustryOrderSource::getEntityNo, ref.scope().entityNo())
                                .eq(OrdIndustryOrderSource::getStoreNo, ref.scope().storeNo())
                                .eq(OrdIndustryOrderSource::getOrderNo, ref.orderNo())
                                .eq(OrdIndustryOrderSource::getSubOrderNo, ref.subOrderNo()));
        if (source == null
                || !ref.source().sourceSystem().equals(source.getSourceSystem())
                || !ref.source().sourceType().equals(source.getSourceType())
                || !ref.source().sourceNo().equals(source.getSourceNo())
                || ref.source().sourceVersion() != source.getSourceVersion()) throw forbidden();
        var order =
                orders.selectOne(
                        Wrappers.<OrdOrder>lambdaQuery()
                                .eq(OrdOrder::getTenantNo, ref.scope().tenantNo())
                                .eq(OrdOrder::getOrderNo, ref.orderNo())
                                .last(lock ? "FOR UPDATE" : ""));
        var sub =
                subs.selectOne(
                        Wrappers.<OrdSubOrder>lambdaQuery()
                                .eq(OrdSubOrder::getTenantNo, ref.scope().tenantNo())
                                .eq(OrdSubOrder::getOrderNo, ref.orderNo())
                                .eq(OrdSubOrder::getSubOrderNo, ref.subOrderNo())
                                .eq(OrdSubOrder::getEntityNo, ref.scope().entityNo())
                                .eq(OrdSubOrder::getStoreNo, ref.scope().storeNo())
                                .last(lock ? "FOR UPDATE" : ""));
        if (order == null || sub == null) throw missing();
        var original =
                items.selectList(
                        Wrappers.<OrdItem>lambdaQuery()
                                .eq(OrdItem::getTenantNo, ref.scope().tenantNo())
                                .eq(OrdItem::getOrderNo, ref.orderNo())
                                .eq(OrdItem::getSubOrderNo, ref.subOrderNo()));
        return new Facts(source, order, sub, original);
    }

    private void eligible(Facts f, long version) {
        if (!"POSTPAY".equals(f.source().getOrderMode())
                || !"ACCEPTED".equals(f.source().getStatus())
                || !OrdOrder.WAIT_PAY.equals(f.order().getStatus())
                || f.order().getPaidAt() != null
                || !Set.of(OrdSubOrder.WAIT_FULFILL, OrdSubOrder.FULFILLING, OrdSubOrder.COMPLETED)
                        .contains(f.sub().getStatus())
                || !Objects.equals(f.sub().getVersion(), version)) throw conflict();
    }

    private void exclusive(Facts f) {
        if (persistence.pendingAmendments(f.source().getTenantNo(), f.sub().getSubOrderNo()) > 0
                || persistence.pendingFulfillments(
                                f.source().getTenantNo(), f.sub().getSubOrderNo())
                        > 0) throw conflict();
    }

    private InventoryDemandPlanPort.Plan plan(Facts f) {
        if (f.source().getDemandPlanSnapshot() == null)
            throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        var p =
                json.readValue(
                        f.source().getDemandPlanSnapshot(), InventoryDemandPlanPort.Plan.class);
        InventoryDemandPlanFacts.validate(p);
        if (!p.planHash().equals(f.source().getDemandPlanHash())
                || !p.scope().externalRef().equals(f.order().getOrderNo())) throw conflict();
        return p;
    }

    private List<IndustryOrderPort.Allocation> allocations(Facts f) {
        return json.readValue(f.source().getAllocationsJson(), new TypeReference<>() {});
    }

    private BigDecimal reduced(Reference ref, Long item, ReductionColumn column) {
        return persistence.reduced(ref, item, column);
    }

    private long moneyReduced(Reference ref, Long item) {
        return persistence.moneyReduced(ref, item);
    }

    private long effectiveDebt(Reference ref, Long base) {
        if (base == null) throw conflict();
        return Math.addExact(
                base,
                persistence.receivableAdjustment(
                        ref.scope().tenantNo(), ref.scope().entityNo(), ref.orderNo()));
    }

    private Receipt project(Journal row, Reference ref) {
        var f = facts(ref, false);
        var ls =
                persistence.lines(row.amendmentNo()).stream()
                        .map(
                                l ->
                                        new Line(
                                                l.orderItemId(),
                                                l.sourceComponentRef(),
                                                l.quantity(),
                                                l.physicalReductionQty(),
                                                l.unit(),
                                                l.receivableReductionMinor()))
                        .toList();
        return new Receipt(
                row.amendmentNo(),
                ref,
                row.status(),
                !"CONFIRMED".equals(row.status()),
                row.currency(),
                row.receivableDeltaMinor(),
                effectiveDebt(ref, f.order().getPayAmount()),
                f.sub().getVersion(),
                ls,
                row.replacementCartNo());
    }

    private Journal byKey(Reference ref, String key) {
        return persistence.byKey(ref, key);
    }

    private static List<Reduction> canonical(List<Reduction> lines) {
        if (lines == null) throw bad();
        var seen = new HashSet<Long>();
        var result =
                lines.stream()
                        .map(
                                l -> {
                                    if (l == null
                                            || l.orderItemId() == null
                                            || !seen.add(l.orderItemId())) throw bad();
                                    return new Reduction(
                                            l.orderItemId(),
                                            l.sourceComponentRef(),
                                            InventoryDemandPlanFacts.quantity(l.quantity(), false),
                                            l.unit(),
                                            l.disposition());
                                })
                        .sorted(Comparator.comparing(Reduction::orderItemId))
                        .toList();
        return result;
    }

    private void authorize(Reference ref, String actor, String key) {
        if (ref == null
                || ref.scope() == null
                || ref.source() == null
                || !"MAIN".equals(ref.scope().tenantNo())
                || !"FOOD".equals(ref.source().sourceSystem())
                || !"CART".equals(ref.source().sourceType())
                || !MaterialCatalogFacts.validKey(actor, 32)
                || key == null
                || key.isBlank()
                || key.length() > 128) throw bad();
        if (!merchants.entityActive("MAIN", ref.scope().entityNo())
                || !merchants.storesOwned(
                        "MAIN", ref.scope().entityNo(), Set.of(ref.scope().storeNo())))
            throw forbidden();
    }

    private static void sameReference(Journal row, Reference ref) {
        if (!ref.orderNo().equals(row.orderNo())
                || !ref.subOrderNo().equals(row.subOrderNo())
                || !ref.scope().entityNo().equals(row.entityNo())
                || !ref.scope().storeNo().equals(row.storeNo())) throw forbidden();
    }

    private static void same(Journal row, String hash) {
        if (!hash.equals(row.requestHash())) throw conflict();
    }

    private static String operation(Reference ref, String key) {
        return PricingSnapshotHash.of(List.of("INDUSTRY_AMENDMENT_KEY_V1", ref.scope(), key));
    }

    private static <T> T provider(ObjectProvider<T> p) {
        var all = p.orderedStream().limit(2).toList();
        if (all.size() != 1) throw BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED);
        return all.getFirst();
    }

    private static void outside() {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Amendment workflow cannot join a caller transaction");
    }

    private static <T> T sql(Supplier<T> work) {
        return DataScopeContext.executeWithoutScope(work);
    }

    private <T> T write(Supplier<T> work) {
        return sql(() -> tx.execute(s -> work.get()));
    }

    private static BizException bad() {
        return BizException.of(ErrorCode.BAD_REQUEST);
    }

    private static BizException conflict() {
        return BizException.of(ErrorCode.CONFLICT);
    }

    private static BizException forbidden() {
        return BizException.of(ErrorCode.FORBIDDEN);
    }

    private static BizException missing() {
        return BizException.of(ErrorCode.NOT_FOUND);
    }
}
