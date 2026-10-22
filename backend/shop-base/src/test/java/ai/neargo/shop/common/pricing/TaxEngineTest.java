package ai.neargo.shop.common.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ai.neargo.shop.common.pricing.TaxTestData.context;
import static ai.neargo.shop.common.pricing.TaxTestData.line;
import static ai.neargo.shop.common.pricing.TaxTestData.policy;
import static ai.neargo.shop.common.pricing.TaxTestData.portion;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import ai.neargo.shop.common.pricing.TaxEngine.Context;
import ai.neargo.shop.common.pricing.TaxEngine.TaxFailure;
import ai.neargo.shop.common.pricing.TaxEngine.TaxPointStatus;

class TaxEngineTest {
    private final TaxEngine engine = new TaxEngine();

    @Test
    void inclusiveExclusiveMixedClassesAndLineRounding() {
        var exclusive = policy(PriceTaxMode.EXCLUSIVE);
        var result = engine.estimate(List.of(line("L1", 10000, TaxClass.STANDARD,
                PriceTaxMode.EXCLUSIVE)), exclusive, context());
        assertThat(result.invoiceNet().minor()).isEqualTo(10000);
        assertThat(result.invoiceTax().minor()).isEqualTo(500);
        assertThat(result.invoiceGross().minor()).isEqualTo(10500);
        var inclusive = engine.estimate(List.of(line("L1", 10500, TaxClass.STANDARD,
                PriceTaxMode.INCLUSIVE)), policy(PriceTaxMode.INCLUSIVE), context());
        assertThat(inclusive.invoiceNet().minor()).isEqualTo(10000);
        assertThat(inclusive.invoiceTax().minor()).isEqualTo(500);
        assertThat(inclusive.invoiceGross().minor()).isEqualTo(10500);
        var mixed = engine.estimate(List.of(line("L1", 10000, TaxClass.STANDARD, PriceTaxMode.EXCLUSIVE),
                line("L2", 5000, TaxClass.ZERO_RATED, PriceTaxMode.EXCLUSIVE),
                line("L3", 2000, TaxClass.EXEMPT, PriceTaxMode.EXCLUSIVE),
                line("L4", 1000, TaxClass.OUT_OF_SCOPE, PriceTaxMode.EXCLUSIVE)), exclusive, context());
        assertThat(mixed.invoiceGross().minor()).isEqualTo(18500);
        assertThat(mixed.invoiceTax().minor()).isEqualTo(500);
        var rounded = engine.estimate(List.of(line("L1", 10, TaxClass.STANDARD, PriceTaxMode.EXCLUSIVE),
                line("L2", 10, TaxClass.STANDARD, PriceTaxMode.EXCLUSIVE)), exclusive, context());
        assertThat(rounded.invoiceTax().minor()).isEqualTo(2);
        assertThat(rounded.invoiceGross().minor()).isEqualTo(22);
    }

    @Test
    void promotionalAndAaPortionsDoNotCreateNewTaxRoundingLines() {
        var result = engine.estimate(List.of(
                completeInvoicePortion(0, 1, TaxClass.STANDARD),
                completeInvoicePortion(1, 2, TaxClass.STANDARD)),
                policy(PriceTaxMode.EXCLUSIVE), context());
        assertThat(result.lines()).hasSize(1);
        assertThat(result.lines().getFirst().sources()).hasSize(2);
        assertThat(result.invoiceGross().minor()).isEqualTo(21);
        assertThat(result.invoiceTax().minor()).isEqualTo(1);
    }

    @Test
    void unknownClassAndInconsistentInvoiceClassAndOverlappingPortionsAreBlocked() {
        var p = policy(PriceTaxMode.EXCLUSIVE);
        assertCode("TAX_CLASS_UNKNOWN", () -> engine.estimate(List.of(
                line("L1", 100, TaxClass.UNKNOWN, PriceTaxMode.EXCLUSIVE)), p, context()));
        assertCode("TAX_INVOICE_LINE_CONFLICT", () -> engine.estimate(List.of(
                completeInvoicePortion(0, 1, TaxClass.STANDARD),
                completeInvoicePortion(1, 2, TaxClass.ZERO_RATED)), p, context()));
        assertThatThrownBy(() -> engine.estimate(List.of(
                line("L1", 10, TaxClass.STANDARD, PriceTaxMode.EXCLUSIVE),
                line("L1", 10, TaxClass.STANDARD, PriceTaxMode.EXCLUSIVE)), p, context()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void supplierPlaceCurrencyAndLegalContextMustMatchPolicy() {
        var p = policy(PriceTaxMode.EXCLUSIVE);
        var lines = List.of(line("L1", 100, TaxClass.STANDARD, PriceTaxMode.EXCLUSIVE));
        var foreign = new Context("AE", null, "SA", null, "TRN", "VERIFIED_TEST_CONTEXT",
                TaxTestData.POINT, TaxPointStatus.CONFIRMED, "a".repeat(64), 1);
        assertCode("TAX_POLICY_CONTEXT_MISMATCH", () -> engine.estimate(lines, p, foreign));
        var unauthorized = new Context("AE", null, "AE", null, "TRN", "OTHER",
                TaxTestData.POINT, TaxPointStatus.CONFIRMED, "a".repeat(64), 1);
        assertCode("TAX_POLICY_CONTEXT_MISMATCH", () -> engine.estimate(lines, p, unauthorized));
        assertThatThrownBy(() -> new Context("AE", null, "AE", null, null, "VERIFIED_TEST_CONTEXT",
                TaxTestData.POINT, TaxPointStatus.CONFIRMED, "a".repeat(64), 1))
                .isInstanceOf(IllegalArgumentException.class);
        var source = lines.getFirst().source();
        var usd = new PricingUnit("L1", null, "G1", "SKU1", "R1", "L1", source.quantityStart(),
                source.quantityEnd(), new MoneyMinor("USD", 100), new MoneyMinor("USD", 100));
        assertCode("TAX_CURRENCY_MISMATCH", () -> engine.estimate(List.of(new TaxEngine.Line(
                usd, TaxClass.STANDARD, PriceTaxMode.EXCLUSIVE, usd.baseAmount())), p, context()));
    }

    @Test
    void policyValidityIsHalfOpenAtTrustedTaxPointAndFinalRequiresConfirmedPoint() {
        var p = policy(PriceTaxMode.INCLUSIVE);
        var limited = new TaxPolicy(p.policyNo(), p.version(), p.countryCode(), null, p.currency(),
                TaxTestData.POINT, TaxTestData.POINT.plusSeconds(1), p.priceMode(), p.allowedContexts(),
                p.rates(), p.algorithm(), p.evidenceRef());
        var lines = List.of(line("L1", 10500, TaxClass.STANDARD, PriceTaxMode.INCLUSIVE));
        assertThat(engine.estimate(lines, limited, context()).invoiceTax().minor()).isEqualTo(500);
        assertCode("TAX_POLICY_EXPIRED", () -> engine.estimate(lines, limited,
                TaxTestData.context(TaxTestData.POINT.plusSeconds(1), TaxPointStatus.CONFIRMED)));
        assertCode("TAX_POLICY_EXPIRED", () -> engine.estimate(lines, limited,
                TaxTestData.context(TaxTestData.POINT.minusSeconds(1), TaxPointStatus.CONFIRMED)));
        var estimate = engine.estimate(lines, p, TaxTestData.context(TaxTestData.POINT, TaxPointStatus.ESTIMATED));
        assertThat(estimate.context().taxPointStatus()).isEqualTo(TaxPointStatus.ESTIMATED);
        assertCode("TAX_POINT_UNCONFIRMED", () -> TaxTestData.finish(estimate, List.of(),
                TaxTestData.funding(0, 0), p));
    }

    @Test
    void noPolicyOrUnsupportedModeAlgorithmOrRateDefaultsToZeroTax() {
        var p = policy(PriceTaxMode.EXCLUSIVE);
        var lines = List.of(line("L1", 100, TaxClass.STANDARD, PriceTaxMode.EXCLUSIVE));
        assertCode("TAX_POLICY_MISSING", () -> engine.estimate(lines, null, context()));
        assertCode("TAX_PRICE_MODE_MISMATCH", () -> engine.estimate(lines,
                policy(PriceTaxMode.INCLUSIVE), context()));
        var unsupported = new TaxPolicy(p.policyNo(), 1, "AE", null, "AED", p.effectiveFrom(), null,
                p.priceMode(), Set.of("VERIFIED_TEST_CONTEXT"), p.rates(), "SCRIPT", "E1");
        assertCode("TAX_ALGORITHM_UNSUPPORTED", () -> engine.estimate(lines, unsupported, context()));
        var missingRates = new TaxPolicy(p.policyNo(), 1, "AE", null, "AED", p.effectiveFrom(), null,
                p.priceMode(), p.allowedContexts(), Map.of(), p.algorithm(), "E1");
        assertCode("TAX_CLASS_UNKNOWN", () -> engine.estimate(lines, missingRates, context()));
    }

    @Test
    void largeIntermediateMultiplicationIsExactAndUnsafeTotalsFailWithoutPartialOutput() {
        var p = policy(PriceTaxMode.INCLUSIVE);
        var result = engine.estimate(List.of(line("L1", 9_000_000_000_000_000L,
                TaxClass.STANDARD, PriceTaxMode.INCLUSIVE)), p, context());
        assertThat(result.invoiceTax().minor()).isEqualTo(428_571_428_571_429L);
        assertThat(result.invoiceGross().minor()).isEqualTo(9_000_000_000_000_000L);
        assertThatThrownBy(() -> engine.estimate(List.of(line("L1", 9_000_000_000_000_000L,
                TaxClass.STANDARD, PriceTaxMode.EXCLUSIVE)), policy(PriceTaxMode.EXCLUSIVE), context()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void policyAndEvidenceCollectionsAreDeeplyImmutable() {
        var p = policy(PriceTaxMode.INCLUSIVE);
        var result = engine.estimate(List.of(line("L1", 10500, TaxClass.STANDARD,
                PriceTaxMode.INCLUSIVE)), p, context());
        assertThatThrownBy(() -> p.rates().put(TaxClass.STANDARD, 0))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.lines().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.lines().getFirst().sources().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void partialAaScopeCannotRecalculateOriginalInvoiceTax() {
        var half = completeInvoicePortion(0, 1, TaxClass.STANDARD);
        assertCode("TAX_INVOICE_LINE_INCOMPLETE", () -> engine.estimate(List.of(half),
                policy(PriceTaxMode.EXCLUSIVE), context()));
    }

    @Test
    void regionSpecificPolicyCannotLeakToOtherSupplierOrSupplyRegion() {
        var p = policy(PriceTaxMode.EXCLUSIVE);
        var regional = new TaxPolicy(p.policyNo(), 1, "AE", "DUBAI", p.currency(), p.effectiveFrom(), null,
                p.priceMode(), p.allowedContexts(), p.rates(), p.algorithm(), p.evidenceRef());
        var allowed = new Context("AE", "DUBAI", "AE", "DUBAI", "TRN", "VERIFIED_TEST_CONTEXT",
                TaxTestData.POINT, TaxPointStatus.CONFIRMED, "a".repeat(64), 1);
        var lines = List.of(line("L1", 100, TaxClass.STANDARD, PriceTaxMode.EXCLUSIVE));
        assertThat(engine.estimate(lines, regional, allowed).invoiceTax().minor()).isEqualTo(5);
        assertCode("TAX_POLICY_CONTEXT_MISMATCH", () -> engine.estimate(lines, regional, context()));
        var wrongPlace = new Context("AE", "DUBAI", "AE", "OTHER", "TRN", "VERIFIED_TEST_CONTEXT",
                TaxTestData.POINT, TaxPointStatus.CONFIRMED, "a".repeat(64), 1);
        assertCode("TAX_POLICY_CONTEXT_MISMATCH", () -> engine.estimate(lines, regional, wrongPlace));
    }

    @Test
    void invalidRateSemanticsCannotPretendToBeZeroOrNonTaxable() {
        var p = policy(PriceTaxMode.INCLUSIVE);
        for (var entry : List.of(Map.entry(TaxClass.STANDARD, 0), Map.entry(TaxClass.ZERO_RATED, 500),
                Map.entry(TaxClass.EXEMPT, 0), Map.entry(TaxClass.OUT_OF_SCOPE, 0),
                Map.entry(TaxClass.STANDARD, 10001))) {
            var rates = new LinkedHashMap<>(p.rates());
            rates.put(entry.getKey(), entry.getValue());
            assertThatThrownBy(() -> new TaxPolicy(p.policyNo(), 1, "AE", null, p.currency(),
                    p.effectiveFrom(), null, p.priceMode(), p.allowedContexts(), rates, p.algorithm(), "E1"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private static TaxEngine.Line completeInvoicePortion(int start, int end, TaxClass taxClass) {
        var partial = portion("SRC", "INV", start, end, 10, taxClass, PriceTaxMode.EXCLUSIVE);
        return new TaxEngine.Line(partial.source(), taxClass, PriceTaxMode.EXCLUSIVE, TaxTestData.money(20));
    }

    static void assertCode(String code, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(TaxFailure.class)
                .extracting(error -> ((TaxFailure) error).code()).isEqualTo(code);
    }
}
