package ai.neargo.shop.common.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.spi.trade.PricingFeeFacts.Application;
import ai.neargo.shop.spi.trade.PricingFeeFacts.Definition;
import ai.neargo.shop.spi.trade.PricingFeeFacts.Entry;
import ai.neargo.shop.spi.trade.PricingFeeFacts.Kind;
import ai.neargo.shop.spi.trade.PricingFeeFacts.Method;
import ai.neargo.shop.spi.trade.PricingFeeFacts.Plan;
import ai.neargo.shop.spi.trade.PricingFeeFacts.Rule;
import ai.neargo.shop.spi.trade.PricingFeeFacts.ScopeBasis;
import ai.neargo.shop.spi.trade.PricingFeeFacts.Source;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Scope;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

class PricingFeeEngineTest {
    private final Scope scope = new Scope("MAIN", "E", "S");
    private final PricingFeeEngine engine = new PricingFeeEngine();

    private PricingUnit unit(String line, long amount) {
        return new PricingUnit(
                line,
                null,
                "G",
                "SKU",
                "PRICE",
                line,
                BigDecimal.ZERO,
                BigDecimal.ONE,
                new MoneyMinor("CNY", amount),
                new MoneyMinor("CNY", amount));
    }

    private PricingFacts prices(long amount) {
        return new PricingFacts("CNY", "a".repeat(64), 0, List.of(unit("L", amount)));
    }

    private Rule rule(Kind kind, Method method, long amount, Integer rate) {
        return new Rule(
                scope,
                "RULE",
                "REV",
                0,
                "ACTIVE",
                new Definition(kind, method, "ORDER", "CNY", amount, rate, TaxClass.STANDARD));
    }

    private Source source(Kind kind) {
        return new Source(
                "RULE", "REV", "CHECK", kind, BigDecimal.ONE, "ORDER", List.of("L"), null);
    }

    @Test
    void repeatedCheckRoundsChargeOnlyRemainingFeeAndConserveOriginalPortion() {
        var first =
                engine.calculate(
                        prices(1000),
                        source(Kind.SERVICE),
                        rule(Kind.SERVICE, Method.FIXED, 300, null),
                        new ScopeBasis("CHECK", "RULE", "CNY", 0, 0, -1),
                        List.of());
        assertThat(first.additionalFeeMinor()).isEqualTo(300);
        assertThat(first.entries())
                .extracting(Entry::portion)
                .containsExactly(PricingPortion.from(unit("L", 1000)));
        var next =
                engine.calculate(
                        prices(1000),
                        source(Kind.SERVICE),
                        rule(Kind.SERVICE, Method.FIXED, 300, null),
                        new ScopeBasis("CHECK", "RULE", "CNY", 1000, 300, 1),
                        List.of());
        assertThat(next.additionalFeeMinor()).isZero();
    }

    @Test
    void minimumReductionProducesOriginalApplicationAdviceInsteadOfNegativeNewFee() {
        var app =
                new Application(
                        "APP", "O", "SUB", "Q", "RULE", "REV", "CHECK", "CNY", 900, 900, "h");
        var result =
                engine.calculate(
                        prices(1000),
                        source(Kind.MINIMUM),
                        rule(Kind.MINIMUM, Method.MINIMUM, 1000, null),
                        new ScopeBasis("CHECK", "RULE", "CNY", 100, 900, 1),
                        List.of(app));
        assertThat(result.additionalFeeMinor()).isZero();
        assertThat(result.targetFeeMinor()).isZero();
        assertThat(result.corrections()).hasSize(1);
        assertThat(result.corrections().getFirst().applicationNo()).isEqualTo("APP");
        assertThat(result.corrections().getFirst().targetFeeMinor()).isZero();
        var plan = new Plan(List.of(result), PricingSnapshotHash.of(List.of(result)));
        engine.validatePlan(plan, prices(1000));
    }

    @Test
    void standardFeeKeepsItsOwnTaxClassWhenGoodsAreExempt() {
        var result =
                engine.calculate(
                        prices(1000),
                        source(Kind.SERVICE),
                        rule(Kind.SERVICE, Method.FIXED, 200, null),
                        new ScopeBasis("CHECK", "RULE", "CNY", 0, 0, -1),
                        List.of());
        var plan = new Plan(List.of(result), PricingSnapshotHash.of(List.of(result)));
        var tax =
                new MerchantTaxSnapshot(
                        "MAIN",
                        "E",
                        "S",
                        1,
                        MerchantTaxSnapshot.Source.MERCHANT_CUSTOM,
                        "E",
                        1,
                        1000,
                        PriceTaxMode.EXCLUSIVE,
                        new TaxSettingDefinition(true, 1000, PriceTaxMode.EXCLUSIVE)
                                .snapshotHash("MERCHANT_CUSTOM:E", 1));
        var input =
                List.of(
                        new TaxEngine.Line(
                                unit("L", 1000),
                                TaxClass.EXEMPT,
                                PriceTaxMode.EXCLUSIVE,
                                new MoneyMinor("CNY", 1000)));
        var computed = new PricingFeeTaxEngine().calculate(input, plan, tax, "a".repeat(64), 0);
        assertThat(computed.fees().values()).containsExactly(200L);
        assertThat(computed.taxes().values()).containsExactly(20L);
    }

    @Test
    void missingBasisAndForgedFeePlanAreRejected() {
        var bad =
                new Source(
                        "RULE",
                        "REV",
                        "CHECK",
                        Kind.SERVICE,
                        BigDecimal.ONE,
                        "ORDER",
                        List.of("FOREIGN"),
                        null);
        assertThatThrownBy(
                        () ->
                                engine.calculate(
                                        prices(1000),
                                        bad,
                                        rule(Kind.SERVICE, Method.FIXED, 200, null),
                                        new ScopeBasis("CHECK", "RULE", "CNY", 0, 0, -1),
                                        List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> engine.validatePlan(new Plan(List.of(), "x"), prices(1000)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void minimumCanExceedGoodsAndZeroFeeKeepsOriginalTaxPortions() {
        var result =
                engine.calculate(
                        prices(100),
                        source(Kind.MINIMUM),
                        rule(Kind.MINIMUM, Method.MINIMUM, 1000, null),
                        new ScopeBasis("CHECK", "RULE", "CNY", 0, 0, -1),
                        List.of());
        assertThat(result.additionalFeeMinor()).isEqualTo(900);
        assertThat(result.entries().stream().mapToLong(Entry::amountMinor).sum()).isEqualTo(900);
        var zero =
                engine.calculate(
                        prices(1000),
                        source(Kind.MINIMUM),
                        rule(Kind.MINIMUM, Method.MINIMUM, 1000, null),
                        new ScopeBasis("CHECK", "RULE", "CNY", 0, 0, -1),
                        List.of());
        assertThat(zero.entries()).hasSize(1);
        assertThat(zero.entries().getFirst().amountMinor()).isZero();
        assertThat(PricingFeeAllocation.allocate(1001, List.of(0L, 0L)))
                .containsExactly(501L, 500L);
    }
}
