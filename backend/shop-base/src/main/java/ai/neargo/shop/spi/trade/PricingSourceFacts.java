package ai.neargo.shop.spi.trade;

import ai.neargo.shop.common.pricing.MoneyMinor;
import ai.neargo.shop.common.pricing.PricingFacts;
import ai.neargo.shop.common.pricing.PricingPortion;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.common.pricing.PricingUnit;
import ai.neargo.shop.common.pricing.SourcePriceMath;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Component;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.InvoiceBasis;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Kind;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Scope;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Snapshot;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/** Shared source receipt checks; a matching digest is integrity evidence, never authentication. */
public final class PricingSourceFacts {
    private PricingSourceFacts() {}

    public static Snapshot freeze(
            Scope scope,
            String subject,
            PricingSourceRef source,
            String channel,
            String sourceHash,
            List<Component> composition) {
        require(composition != null && !composition.isEmpty() && composition.size() <= 1024);
        var chargeUnits = chargeUnits(composition);
        var prices =
                new PricingFacts(
                        chargeUnits.getFirst().baseAmount().currency(),
                        sourceHash,
                        source.sourceVersion(),
                        chargeUnits);
        var draft =
                new Snapshot(
                        1,
                        scope,
                        subject,
                        source,
                        channel,
                        prices,
                        composition,
                        invoices(chargeUnits),
                        null);
        var result =
                new Snapshot(
                        1,
                        scope,
                        subject,
                        source,
                        channel,
                        prices,
                        composition,
                        draft.invoiceBases(),
                        digest(draft));
        return validate(result);
    }

    public static Snapshot withFees(Snapshot source, List<PricingFeeFacts.Source> fees) {
        if (fees == null || fees.isEmpty()) return source;
        validate(source);
        var draft =
                new Snapshot(
                        source.schemaVersion(),
                        source.scope(),
                        source.subjectNo(),
                        source.source(),
                        source.channel(),
                        source.prices(),
                        source.composition(),
                        source.invoiceBases(),
                        null,
                        fees);
        return validate(
                new Snapshot(
                        draft.schemaVersion(),
                        draft.scope(),
                        draft.subjectNo(),
                        draft.source(),
                        draft.channel(),
                        draft.prices(),
                        draft.composition(),
                        draft.invoiceBases(),
                        digest(draft),
                        fees));
    }

    public static Snapshot validate(Snapshot value) {
        require(
                value != null
                        && value.schemaVersion() == 1
                        && value.scope() != null
                        && value.source() != null
                        && value.prices() != null
                        && value.composition() != null
                        && !value.composition().isEmpty()
                        && value.composition().size() <= 1024
                        && value.invoiceBases() != null);
        key(value.channel());
        if (value.fees() != null) {
            require(value.fees().size() <= 32);
            var feeKeys = new HashSet<String>();
            for (var fee : value.fees()) {
                require(fee != null);
                key(fee.ruleNo());
                key(fee.revisionNo());
                key(fee.scopeNo());
                require(
                        feeKeys.add(fee.ruleNo() + ":" + fee.scopeNo())
                                && fee.kind() != null
                                && fee.quantity() != null
                                && fee.quantity().signum() > 0
                                && fee.quantity().scale() <= 6
                                && fee.unit() != null
                                && fee.basisSourceRefs() != null
                                && !fee.basisSourceRefs().isEmpty());
            }
        }
        if (value.subjectNo() != null) key(value.subjectNo());
        require(value.prices().sourceVersion() == value.source().sourceVersion());
        var identities = new HashSet<List<String>>();
        for (var component : value.composition()) {
            require(component != null && component.kind() != null && component.unit() != null);
            var unit = component.unit();
            require(unit.baseAmount().currency().equals(value.prices().currency()));
            // A complete source snapshot starts at zero; AA portions need their later, separate
            // scope contract.
            require(
                    unit.quantityStart().signum() == 0
                            && identities.add(
                                    List.of(
                                            unit.sourceLineNo(),
                                            Objects.toString(unit.componentRef(), ""))));
            require(
                    SourcePriceMath.extend(unit.unitPrice(), unit.quantity())
                            .equals(unit.baseAmount()));
            if (component.kind() == Kind.BUNDLE_INCLUDED) {
                require(unit.unitPrice().minor() == 0 && unit.baseAmount().minor() == 0);
            }
        }
        var charged = chargeUnits(value.composition());
        require(charged.equals(value.prices().units()));
        require(invoices(charged).equals(value.invoiceBases()));
        require(digest(value).equals(value.snapshotHash()));
        return value;
    }

    public static Snapshot expected(
            Snapshot value, Scope scope, PricingSourceRef source, String subject) {
        validate(value);
        require(
                source.equals(value.source())
                        && (scope == null || scope.equals(value.scope()))
                        && Objects.equals(subject, value.subjectNo()));
        return value;
    }

    private static List<PricingUnit> chargeUnits(List<Component> composition) {
        var result = new ArrayList<PricingUnit>();
        for (var component : composition) {
            require(component != null && component.kind() != null && component.unit() != null);
            if (component.kind() != Kind.BUNDLE_INCLUDED) result.add(component.unit());
        }
        require(!result.isEmpty());
        return List.copyOf(result);
    }

    private static List<InvoiceBasis> invoices(List<PricingUnit> units) {
        var grouped = new LinkedHashMap<String, List<PricingUnit>>();
        for (var unit : units)
            grouped.computeIfAbsent(unit.taxInvoiceLineRef(), key -> new ArrayList<>()).add(unit);
        var result = new ArrayList<InvoiceBasis>();
        for (var entry : grouped.entrySet()) {
            var amount = new MoneyMinor(entry.getValue().getFirst().baseAmount().currency(), 0);
            for (var unit : entry.getValue())
                amount = amount.add(unit.baseAmount()).requireJsonSafe();
            result.add(
                    new InvoiceBasis(
                            entry.getKey(),
                            entry.getValue().stream().map(PricingPortion::from).toList(),
                            amount));
        }
        return List.copyOf(result);
    }

    private static String digest(Snapshot value) {
        return PricingSnapshotHash.of(
                new Snapshot(
                        value.schemaVersion(),
                        value.scope(),
                        value.subjectNo(),
                        value.source(),
                        value.channel(),
                        value.prices(),
                        value.composition(),
                        value.invoiceBases(),
                        null,
                        value.fees()));
    }

    public static void key(String value) {
        require(
                value != null
                        && !value.isBlank()
                        && value.length() <= 32
                        && value.codePoints()
                                .noneMatch(
                                        c ->
                                                Character.isWhitespace(c)
                                                        || Character.isSpaceChar(c)
                                                        || Character.isISOControl(c)));
    }

    private static void require(boolean valid) {
        if (!valid) throw new IllegalArgumentException("Invalid source pricing snapshot");
    }
}
