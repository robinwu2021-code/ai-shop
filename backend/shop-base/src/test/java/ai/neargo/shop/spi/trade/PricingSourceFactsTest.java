package ai.neargo.shop.spi.trade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.pricing.MoneyMinor;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.common.pricing.PricingUnit;
import ai.neargo.shop.common.pricing.PriceTaxMode;
import ai.neargo.shop.common.pricing.TaxEngine;
import ai.neargo.shop.common.pricing.TaxClass;
import ai.neargo.shop.common.pricing.TaxSettingDefinition;
import ai.neargo.shop.common.pricing.MerchantTaxSnapshot;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Component;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Credentials;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Kind;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Scope;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Snapshot;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.junit.jupiter.api.Test;

class PricingSourceFactsTest {
    private final Scope scope = new Scope("MAIN", "E", "A");
    private final PricingSourceRef source = new PricingSourceRef("FOOD", "CART", "C", 4, null, null);
    private PricingUnit unit(String root, String component, long price, String quantity, String invoice) {
        var qty = new BigDecimal(quantity);
        return new PricingUnit(root, component, "G", "S", "PRICE", invoice, BigDecimal.ZERO, qty,
                new MoneyMinor("CNY", price), new MoneyMinor("CNY", BigDecimal.valueOf(price).multiply(qty)
                        .setScale(0, RoundingMode.HALF_UP).longValueExact()));
    }
    private Snapshot snapshot() {
        return PricingSourceFacts.freeze(scope, null, source, "POS", "a".repeat(64), List.of(
                new Component(Kind.MAIN, unit("L", "MAIN", 5, "1", "L")),
                new Component(Kind.ADDON, unit("L", "ADD", 5, "1", "L")),
                new Component(Kind.BUNDLE_INCLUDED, unit("L", "INCLUDED", 0, "2", "L"))));
    }
    @Test void preservesZeroIncludedCompositionAndOnlyChargesMainAndAdditions() {
        var result = snapshot();
        assertThat(result.composition()).hasSize(3);
        assertThat(result.prices().units()).hasSize(2);
        assertThat(result.prices().baseTotal().minor()).isEqualTo(10);
        assertThat(result.invoiceBases()).hasSize(1);
        assertThat(result.invoiceBases().getFirst().amount().minor()).isEqualTo(10);
        assertThat(result.snapshotHash()).isNotEqualTo(result.prices().sourceSnapshotHash());
        assertThat(PricingSourceFacts.validate(result)).isSameAs(result);
    }
    @Test void originalInvoiceRoundsTaxOnceAcrossMainAndAddon() {
        var result = snapshot();
        var definition = new TaxSettingDefinition(true, 500, PriceTaxMode.EXCLUSIVE);
        var tax = new MerchantTaxSnapshot("MAIN", "E", "A", 0, MerchantTaxSnapshot.Source.PLATFORM_DEFAULT,
                "PLATFORM", 1, 500, PriceTaxMode.EXCLUSIVE, definition.snapshotHash("PLATFORM_DEFAULT:PLATFORM", 1));
        var lines = result.prices().units().stream().map(u -> new TaxEngine.Line(u, TaxClass.STANDARD,
                PriceTaxMode.EXCLUSIVE, result.invoiceBases().getFirst().amount())).toList();
        var calculation = new TaxEngine().estimateConfigured(lines, tax, result.prices().sourceSnapshotHash(), 4);
        assertThat(calculation.lines()).hasSize(1);
        assertThat(calculation.lines().getFirst().tax().minor()).isEqualTo(1);
    }
    @Test void validatesFractionalComponentQuantityAndRejectsDuplicateOrWrongAmount() {
        var fractional = new Component(Kind.ADDON, unit("L", "ADD", 101, "0.5", "L"));
        assertThat(PricingSourceFacts.freeze(scope, null, source, "POS", "b".repeat(64), List.of(fractional))
                .prices().baseTotal().minor()).isEqualTo(51);
        assertThatThrownBy(() -> PricingSourceFacts.freeze(scope, null, source, "POS", "a".repeat(64),
                List.of(fractional, fractional))).isInstanceOf(IllegalArgumentException.class);
        var unit = fractional.unit();
        var wrong = new PricingUnit(unit.sourceLineNo(), unit.componentRef(), unit.goodsNo(), unit.skuNo(),
                unit.priceRevision(), unit.taxInvoiceLineRef(), unit.quantityStart(), unit.quantityEnd(),
                unit.unitPrice(), new MoneyMinor("CNY", 50));
        assertThatThrownBy(() -> PricingSourceFacts.freeze(scope, null, source, "POS", "a".repeat(64),
                List.of(new Component(Kind.ADDON, wrong)))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsNonzeroIncludedEvenWhenTotalExcludesIt() {
        assertThatThrownBy(() -> PricingSourceFacts.freeze(scope, null, source, "POS", "a".repeat(64),
                List.of(new Component(Kind.MAIN, unit("L", "MAIN", 10, "1", "L")),
                        new Component(Kind.BUNDLE_INCLUDED, unit("L", "INC", 3, "1", "L")))))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsMissingOrAlteredInvoiceBasisEvenAfterRehash() {
        var p = snapshot();
        var partial = new Snapshot(1, p.scope(), p.subjectNo(), p.source(), p.channel(), p.prices(), p.composition(),
                List.of(), null);
        var bad = new Snapshot(1, p.scope(), p.subjectNo(), p.source(), p.channel(), p.prices(), p.composition(),
                List.of(), PricingSnapshotHash.of(partial));
        assertThatThrownBy(() -> PricingSourceFacts.validate(bad)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsForeignScopeSubjectSourceVersionAndOldProjectionHash() {
        var p = snapshot();
        assertThatThrownBy(() -> PricingSourceFacts.expected(p, new Scope("MAIN", "E", "OTHER"), source, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PricingSourceFacts.expected(p, scope, source, "GUEST"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PricingSourceFacts.expected(p, scope,
                new PricingSourceRef("FOOD", "CART", "C", 5, null, null), null))
                .isInstanceOf(IllegalArgumentException.class);
        var bad = new Snapshot(1, p.scope(), p.subjectNo(), p.source(), "H5", p.prices(), p.composition(),
                p.invoiceBases(), p.snapshotHash());
        assertThatThrownBy(() -> PricingSourceFacts.validate(bad)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void credentialsRemainRedactedAndRequireActualTokenOrBuyer() {
        assertThat(new Credentials(null, "secret", "BUYER").toString()).doesNotContain("secret", "BUYER");
        assertThatThrownBy(() -> new Credentials("GUEST", null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Scope("OTHER", "E", "A")).isInstanceOf(IllegalArgumentException.class);
    }
}
