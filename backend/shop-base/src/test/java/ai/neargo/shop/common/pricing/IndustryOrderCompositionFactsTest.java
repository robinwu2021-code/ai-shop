package ai.neargo.shop.common.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.spi.trade.IndustryOrderCompositionFacts;
import ai.neargo.shop.spi.trade.IndustryOrderPort.Mode;
import ai.neargo.shop.spi.trade.PricingSourceFacts;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Component;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Kind;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Scope;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Snapshot;
import ai.neargo.shop.spi.trade.PricingSourceRef;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

class IndustryOrderCompositionFactsTest {
    private static Component part(Kind kind, String ref, BigDecimal quantity) {
        return new Component(
                kind,
                new PricingUnit(
                        "ROOT",
                        ref.repeat(64),
                        "G",
                        kind == Kind.MAIN ? "MAIN" : "BOBA",
                        "REV",
                        "ROOT",
                        BigDecimal.ZERO,
                        quantity,
                        new MoneyMinor("CNY", 100),
                        new MoneyMinor(
                                "CNY", quantity.multiply(new BigDecimal("100")).longValueExact())));
    }

    private static Snapshot facts(Component... parts) {
        return PricingSourceFacts.freeze(
                new Scope("MAIN", "E", "S"),
                null,
                new PricingSourceRef("FOOD", "CART", "C", 1, null, null),
                "POS",
                "a".repeat(64),
                List.of(parts));
    }

    @Test
    void mainAndMultipleAddonUnitsKeepIndependentQuantityAndSingleInvoiceRoot() {
        var source =
                facts(
                        part(Kind.MAIN, "b", BigDecimal.ONE),
                        part(Kind.ADDON, "c", new BigDecimal("2")));
        IndustryOrderCompositionFacts.require(source, Mode.POSTPAY);
        assertThat(source.prices().units()).hasSize(2);
        assertThat(source.prices().baseTotal().minor()).isEqualTo(300);
    }

    @Test
    void orphanAddonAndPrepayExpansionAreRejected() {
        assertThatThrownBy(
                        () ->
                                IndustryOrderCompositionFacts.require(
                                        facts(part(Kind.ADDON, "c", BigDecimal.ONE)), Mode.POSTPAY))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(
                        () ->
                                IndustryOrderCompositionFacts.require(
                                        facts(
                                                part(Kind.MAIN, "b", BigDecimal.ONE),
                                                part(Kind.ADDON, "c", BigDecimal.ONE)),
                                        Mode.PREPAY))
                .isInstanceOf(BizException.class);
    }

    @Test
    void exactPostpayFractionIsPreservedAndLegacyPrepayStillRejectsFractions() {
        var source = facts(part(Kind.MAIN, "b", new BigDecimal("0.5")));
        IndustryOrderCompositionFacts.require(source, Mode.POSTPAY);
        assertThat(source.composition().getFirst().unit().quantity()).isEqualByComparingTo("0.5");
        assertThat(source.prices().baseTotal().minor()).isEqualTo(50);
        assertThatThrownBy(() -> IndustryOrderCompositionFacts.require(source, Mode.PREPAY))
                .isInstanceOf(BizException.class);
    }
}
