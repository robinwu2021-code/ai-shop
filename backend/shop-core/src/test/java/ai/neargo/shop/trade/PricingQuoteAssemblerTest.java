package ai.neargo.shop.trade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import ai.neargo.shop.common.pricing.BenefitSelection;
import ai.neargo.shop.common.pricing.MerchantTaxSnapshot;
import ai.neargo.shop.common.pricing.MerchantTaxSnapshot.Source;
import ai.neargo.shop.common.pricing.MoneyMinor;
import ai.neargo.shop.common.pricing.PriceTaxMode;
import ai.neargo.shop.common.pricing.PricingFacts;
import ai.neargo.shop.common.pricing.PricingRuleFacts;
import ai.neargo.shop.common.pricing.PricingRuleFacts.UnitPolicy;
import ai.neargo.shop.common.pricing.PricingUnit;
import ai.neargo.shop.common.pricing.PricingPortion;
import ai.neargo.shop.common.pricing.TaxClass;
import ai.neargo.shop.common.pricing.TaxSettingDefinition;
import ai.neargo.shop.spi.trade.PricingSourceRef;
import ai.neargo.shop.trade.assembler.PricingQuoteAssembler;
import ai.neargo.shop.trade.dto.PricingQuoteCommands.Amounts;
import ai.neargo.shop.trade.dto.PricingQuoteCommands.Context;
import ai.neargo.shop.trade.dto.PricingQuoteCommands.Funding;
import ai.neargo.shop.trade.dto.PricingQuoteCommands.Industry;
import ai.neargo.shop.trade.dto.PricingQuoteCommands.Intent;
import ai.neargo.shop.trade.dto.PricingQuoteCommands.Line;
import ai.neargo.shop.trade.dto.PricingQuoteCommands.LineKind;
import ai.neargo.shop.trade.dto.PricingQuoteCommands.Phase;
import ai.neargo.shop.trade.dto.PricingQuoteCommands.SaveReq;
import org.junit.jupiter.api.Test;

class PricingQuoteAssemblerTest {
    private final PricingQuoteAssembler assembler = new PricingQuoteAssembler();
    private final Context context = new Context("MAIN", "M1", "S1", "U1", "ACT");

    @Test
    void snapshotHashIsCanonicalAndLifetimeIsNotEconomics() {
        var first = assembler.prepare(context, draft(lines(), 30), "{\"b\":2,\"a\":1}");
        var second = assembler.prepare(context, draft(lines(), 120), "{\"a\":1,\"b\":2}");
        assertThat(first.economicsHash()).isEqualTo(second.economicsHash());
        assertThat(first.factsHash()).isEqualTo(second.factsHash());
        assertThat(first.requestHash()).isEqualTo(second.requestHash());
        assertThat(assembler.commandHash(draft(lines(), 30)))
                .isNotEqualTo(assembler.commandHash(draft(lines(), 120)));
        assertThat(first.configSnapshot()).isEqualTo("{\"a\":1,\"b\":2}");
    }

    @Test
    void fullInvoicePortionsKeepOneTaxRoundingAndPartialInvoiceFails() {
        var request = draft(lines(), 60);
        assertThat(assembler.prepare(context, request, "{}").amounts().taxMinor()).isEqualTo(1);
        var partial = draft(List.of(lines().getFirst()), 60);
        assertThatThrownBy(() -> assembler.prepare(context, partial, "{}"))
                .hasMessage("TAX_INVOICE_LINE_INCOMPLETE");
    }

    @Test
    void sourceOrderAndScopedTaxSnapshotCannotBeSubstituted() {
        var request = draft(lines(), 60);
        var wrong = new Context("MAIN", "OTHER", "S1", "U1", "ACT");
        assertThatThrownBy(() -> assembler.prepare(wrong, request, "{}"))
                .isInstanceOf(IllegalArgumentException.class);
        var reversed = List.of(lines().get(1), lines().getFirst());
        var changed = new SaveReq(request.source(), request.industry(), request.channel(), request.phase(),
                request.storeRevisionNo(), request.templateRevisionNo(), request.engineSpecVersion(), request.facts(),
                request.tax(), request.intent(), reversed, request.allocations(), 60);
        assertThatThrownBy(() -> assembler.prepare(context, changed, "{}"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void canonicalNumberFormattingAndScopeHashDoNotDependOnActor() {
        assertThat(assembler.canonical(new BigDecimal("1.000000"))).isEqualTo("1");
        assertThat(assembler.scopeHash(context)).isEqualTo(assembler.scopeHash(
                new Context("MAIN", "M1", "S1", "U1", "OTHER")));
        assertThat(assembler.scopeHash(context)).isNotEqualTo(assembler.scopeHash(
                new Context("MAIN", "M1", "S1", "U2", "ACT")));
    }

    @Test
    void duplicateOrTrailingJsonCannotBecomeAConfigSnapshot() {
        var request = draft(lines(), 60);
        for (String json : List.of("{bad}", "{\"a\":1,\"a\":2}", "{} {}")) {
            assertThatThrownBy(() -> assembler.prepare(context, request, json)).isInstanceOf(RuntimeException.class);
        }
    }

    @Test
    void lifetimeAndMonetaryTamperingDoNotProducePartialSnapshots() {
        assertThatThrownBy(() -> assembler.prepare(context, draft(lines(), 301), "{}"))
                .isInstanceOf(IllegalArgumentException.class);
        var source = lines().getFirst().source();
        var wrong = new Line(source, null, LineKind.GOODS, TaxClass.STANDARD,
                new Amounts(10, 0, 0, 0, 0, 0, 0, 0, 11, 1, 12, 0), 20, policy(source));
        assertThatThrownBy(() -> assembler.prepare(context, draft(List.of(wrong, lines().get(1)), 60), "{}"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void paidOrUnknownPortionsAndMixedBundlePricingCannotBeRepriced() {
        var first = lines().getFirst();
        for (var status : List.of(PricingRuleFacts.ScopeStatus.PAID, PricingRuleFacts.ScopeStatus.UNKNOWN)) {
            var policy = new UnitPolicy(PricingPortion.from(first.source()), true, status,
                    null, PricingRuleFacts.PricingRole.NONE);
            var changed = new Line(first.source(), null, first.kind(), first.taxClass(), first.amounts(), 20, policy);
            assertThatThrownBy(() -> assembler.prepare(context, draft(List.of(changed, lines().get(1)), 60), "{}"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        var second = lines().get(1);
        var parent = new Line(first.source(), null, first.kind(), first.taxClass(), first.amounts(), 20,
                new UnitPolicy(PricingPortion.from(first.source()), true, PricingRuleFacts.ScopeStatus.OPEN,
                        "BUNDLE", PricingRuleFacts.PricingRole.PARENT));
        var leaf = new Line(second.source(), null, second.kind(), second.taxClass(), second.amounts(), 20,
                new UnitPolicy(PricingPortion.from(second.source()), true, PricingRuleFacts.ScopeStatus.OPEN,
                        "BUNDLE", PricingRuleFacts.PricingRole.LEAF));
        assertThatThrownBy(() -> assembler.prepare(context, draft(List.of(parent, leaf), 60), "{}"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private SaveReq draft(List<Line> lines, int lifetime) {
        var mode = PriceTaxMode.EXCLUSIVE;
        var tax = new MerchantTaxSnapshot("MAIN", "M1", "S1", 1, Source.MERCHANT_CUSTOM,
                "M1", 1, 500, mode, new TaxSettingDefinition(true, 500, mode).snapshotHash("MERCHANT_CUSTOM:M1", 1));
        var facts = new PricingFacts("AED", "b".repeat(64), 1, lines.stream().map(Line::source).toList());
        return new SaveReq(new PricingSourceRef("FOOD", "CART", "CART1", 1, null, null), Industry.FOOD, "CASHIER",
                Phase.LIVE, "PR1", "TR1", "ENGINE_V1", facts, tax,
                new Intent(new BenefitSelection(BenefitSelection.Mode.EXPLICIT, List.of(), List.of()),
                        new Funding(0, 0, 0, null)), lines, List.of(), lifetime);
    }

    private List<Line> lines() {
        var money = new MoneyMinor("AED", 10);
        var first = new PricingUnit("L1", null, "G1", "SKU1", "P1", "INV", BigDecimal.ZERO,
                BigDecimal.ONE, money, money);
        var second = new PricingUnit("L1", null, "G1", "SKU1", "P1", "INV", BigDecimal.ONE,
                BigDecimal.valueOf(2), money, money);
        return List.of(new Line(first, null, LineKind.GOODS, TaxClass.STANDARD,
                        new Amounts(10, 0, 0, 0, 0, 0, 0, 0, 10, 1, 11, 0), 20, policy(first)),
                new Line(second, null, LineKind.GOODS, TaxClass.STANDARD,
                        new Amounts(10, 0, 0, 0, 0, 0, 0, 0, 10, 0, 10, 0), 20, policy(second)));
    }
    private UnitPolicy policy(PricingUnit unit) {
        return new UnitPolicy(PricingPortion.from(unit), true, PricingRuleFacts.ScopeStatus.OPEN,
                null, PricingRuleFacts.PricingRole.NONE);
    }

}
