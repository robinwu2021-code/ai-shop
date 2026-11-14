package ai.neargo.shop.common.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ai.neargo.shop.common.pricing.TaxTestData.line;
import static ai.neargo.shop.common.pricing.TaxTestData.portion;

import java.util.List;
import org.junit.jupiter.api.Test;
import ai.neargo.shop.common.pricing.MerchantTaxSnapshot.Source;
import ai.neargo.shop.common.pricing.TaxEngine.Line;

class MerchantTaxEngineTest {
    private final TaxEngine engine = new TaxEngine();

    @Test
    void bothModesUseExactSharedArithmeticWithoutRegistrationOrRegionalProfile() {
        var exclusive = estimate(10000, 500, PriceTaxMode.EXCLUSIVE);
        assertThat(exclusive.invoiceNet().minor()).isEqualTo(10000);
        assertThat(exclusive.invoiceTax().minor()).isEqualTo(500);
        assertThat(exclusive.invoiceGross().minor()).isEqualTo(10500);
        var inclusive = estimate(10000, 500, PriceTaxMode.INCLUSIVE);
        assertThat(inclusive.invoiceNet().minor()).isEqualTo(9524);
        assertThat(inclusive.invoiceTax().minor()).isEqualTo(476);
        assertThat(inclusive.invoiceGross().minor()).isEqualTo(10000);
    }

    @Test
    void zeroAndMaximumRatesRemainExplicit() {
        assertThat(estimate(999, 0, PriceTaxMode.EXCLUSIVE).invoiceTax().minor()).isZero();
        assertThat(estimate(999, 10000, PriceTaxMode.EXCLUSIVE).invoiceGross().minor()).isEqualTo(1998);
        assertThat(estimate(999, 10000, PriceTaxMode.INCLUSIVE).invoiceTax().minor()).isEqualTo(500);
    }

    @Test
    void portionsKeepOriginalInvoiceRoundingAndPartialAaCannotBeRetaxed() {
        var first = portion("L1", "INV", 0, 1, 10, TaxClass.STANDARD, PriceTaxMode.EXCLUSIVE);
        var second = portion("L1", "INV", 1, 2, 10, TaxClass.STANDARD, PriceTaxMode.EXCLUSIVE);
        var full = new MoneyMinor("AED", 20);
        var lines = List.of(new Line(first.source(), first.taxClass(), first.priceMode(), full),
                new Line(second.source(), second.taxClass(), second.priceMode(), full));
        var result = engine.estimateConfigured(lines, snapshot(500, PriceTaxMode.EXCLUSIVE), "a".repeat(64), 1);
        assertThat(result.lines()).hasSize(1);
        assertThat(result.invoiceTax().minor()).isEqualTo(1);
        assertThatThrownBy(() -> engine.estimateConfigured(lines.subList(0, 1),
                snapshot(500, PriceTaxMode.EXCLUSIVE), "a".repeat(64), 1))
                .hasMessage("TAX_INVOICE_LINE_INCOMPLETE");
        var separate = List.of(line("A", 10, TaxClass.STANDARD, PriceTaxMode.EXCLUSIVE),
                line("B", 10, TaxClass.STANDARD, PriceTaxMode.EXCLUSIVE));
        assertThat(engine.estimateConfigured(separate, snapshot(500, PriceTaxMode.EXCLUSIVE),
                "a".repeat(64), 1).invoiceTax().minor()).isEqualTo(2);
    }

    @Test
    void malformedSnapshotsAndUnknownClassesCannotProducePartialResults() {
        var snapshot = snapshot(500, PriceTaxMode.EXCLUSIVE);
        assertThatThrownBy(() -> new MerchantTaxSnapshot("MAIN", "M1", "S1", 2, Source.MERCHANT_CUSTOM,
                "M1", 1, 500, PriceTaxMode.EXCLUSIVE, snapshot.contentHash()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MerchantTaxSnapshot("MAIN", "M1", "S1", 1, Source.MERCHANT_CUSTOM,
                "M1", 1, 600, PriceTaxMode.EXCLUSIVE, snapshot.contentHash()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> engine.estimateConfigured(List.of(line("A", 10, TaxClass.UNKNOWN,
                PriceTaxMode.EXCLUSIVE)), snapshot, "a".repeat(64), 1)).hasMessage("TAX_CLASS_UNKNOWN");
        assertThatThrownBy(() -> engine.estimateConfigured(List.of(line("A", 10, TaxClass.STANDARD,
                PriceTaxMode.INCLUSIVE)), snapshot, "a".repeat(64), 1)).hasMessage("TAX_PRICE_MODE_MISMATCH");
    }

    @Test
    void unsafeGrossOverflowIsRejectedAndHistoricalResultDoesNotChange() {
        var previous = estimate(10000, 500, PriceTaxMode.EXCLUSIVE);
        estimate(10000, 700, PriceTaxMode.INCLUSIVE);
        assertThat(previous.invoiceGross().minor()).isEqualTo(10500);
        assertThat(previous.snapshot().rateBp()).isEqualTo(500);
        assertThatThrownBy(() -> estimate(9007199254740991L, 10000, PriceTaxMode.EXCLUSIVE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private TaxEngine.ConfiguredTaxResult estimate(long amount, int rate, PriceTaxMode mode) {
        return engine.estimateConfigured(List.of(line("A", amount, TaxClass.STANDARD, mode)),
                snapshot(rate, mode), "a".repeat(64), 1);
    }

    private MerchantTaxSnapshot snapshot(int rate, PriceTaxMode mode) {
        return new MerchantTaxSnapshot("MAIN", "M1", "S1", 1, Source.MERCHANT_CUSTOM, "M1", 1, rate, mode,
                new TaxSettingDefinition(true, rate, mode).snapshotHash("MERCHANT_CUSTOM:M1", 1));
    }
}
