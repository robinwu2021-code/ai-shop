package ai.neargo.shop.food.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.pricing.MoneyMinor;
import ai.neargo.shop.food.dto.MenuSkuPriceInput;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class MenuCurrencyPolicyTest {
    @Test void structureIsCurrencyAgnosticButExpectedCurrencyComesFromServerContext() {
        var mixed = List.of(new MenuSkuPriceInput("Z", 100L, "AED"), new MenuSkuPriceInput("A", 20L, "TWD"));
        assertThat(MenuPricePolicy.normalizeStructure(mixed)).extracting(MenuSkuPriceInput::skuNo)
                .containsExactly("A", "Z");
        assertThatThrownBy(() -> MenuPricePolicy.normalizeForCurrency(mixed, "AED")).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> MenuPricePolicy.normalize(mixed)).isInstanceOf(BizException.class);
    }

    @Test void exactMinorUnitsAreSelectedWithoutScalingRoundingOrForeignExchange() {
        for (String currency : List.of("AED", "TWD", "JPY", "KWD")) {
            var overrides = List.of(new MenuSkuPriceInput("SKU", 1234L, currency));
            assertThat(MenuPricePolicy.priceForCurrency(overrides, "SKU", new MoneyMinor(currency, 999), currency))
                    .isEqualTo(new MoneyMinor(currency, 1234));
            assertThat(MenuPricePolicy.priceForCurrency(overrides, "OTHER", new MoneyMinor(currency, 999), currency))
                    .isEqualTo(new MoneyMinor(currency, 999));
        }
    }

    @Test void localCnyPriceRejectsForeignNativeCurrencyEvenForEmptyOrUnmatchedOverrides() {
        for (var overrides : List.of(List.<MenuSkuPriceInput>of(), List.of(new MenuSkuPriceInput("OTHER", 1L, "CNY")),
                List.of(new MenuSkuPriceInput("SKU", 1L, "CNY")))) {
            assertThatThrownBy(() -> MenuPricePolicy.price(overrides, "SKU", new MoneyMinor("AED", 100)))
                    .isInstanceOf(BizException.class);
        }
        assertThatThrownBy(() -> MenuPricePolicy.priceForCurrency(null, "SKU", new MoneyMinor("CNY", 10), "AED"))
                .isInstanceOf(BizException.class);
    }

    @Test void duplicateUnknownUnsafeAndNegativePriceFactsNeverNormalize() {
        for (var invalid : List.of(List.of(new MenuSkuPriceInput("S", -1L, "AED")),
                List.of(new MenuSkuPriceInput("S", MoneyMinor.MAX_JSON_SAFE_MINOR + 1, "AED")),
                List.of(new MenuSkuPriceInput("S", 1L, "BOGUS")),
                List.of(new MenuSkuPriceInput("S", 1L, "AED"), new MenuSkuPriceInput("S", 2L, "AED")),
                IntStream.range(0, 201).mapToObj(i -> new MenuSkuPriceInput("S" + i, 1L, "AED")).toList())) {
            assertThatThrownBy(() -> MenuPricePolicy.normalizeStructure(invalid)).isInstanceOf(RuntimeException.class);
        }
    }

    @Test void legacyCnySelectionAndCanonicalNullEmptyMeaningStayUnchanged() {
        assertThat(MenuPricePolicy.normalize(null)).isNull();
        assertThat(MenuPricePolicy.normalize(List.of())).isNull();
        var nativePrice = new MoneyMinor("CNY", 200);
        assertThat(MenuPricePolicy.price(null, "SKU", nativePrice)).isSameAs(nativePrice);
        assertThat(MenuPricePolicy.price(List.of(new MenuSkuPriceInput("SKU", 100L, "CNY")), "SKU", nativePrice))
                .isEqualTo(new MoneyMinor("CNY", 100));
    }

    @Test void structuralCompositionAndFrozenCurrencyValidationAreSeparateFromLegacyCny() {
        var context = CurrencyMarketFixtures.context("AE", "AED", 2);
        var menu = CurrencyMarketFixtures.menu(6, context, List.of(), List.of(),
                List.of(new MenuSkuPriceInput("SKU", 1234L, "AED")));
        var definition = menu.menu().definition().composition();
        assertThat(MenuCompositionDefinitions.normalizeStructure(definition)).isEqualTo(definition);
        assertThat(MenuCompositionDefinitions.normalizeForCurrency(definition, "AED")).isEqualTo(definition);
        assertThatThrownBy(() -> MenuCompositionDefinitions.normalize(definition)).isInstanceOf(BizException.class);
    }
}
