package ai.neargo.shop.food.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.neargo.shop.commerce.dto.ChannelCatalogAuthorityContracts;
import ai.neargo.shop.commerce.policy.ChannelCatalogAuthorityPolicy;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.pricing.MerchantTaxSnapshot;
import ai.neargo.shop.common.pricing.PriceTaxMode;
import ai.neargo.shop.common.pricing.TaxSettingDefinition;
import ai.neargo.shop.food.commerce.dto.FoodCatalogBindingContracts;
import ai.neargo.shop.food.commerce.dto.FoodCatalogPricePolicyContracts;
import ai.neargo.shop.food.commerce.policy.FoodCatalogPricePolicy;
import ai.neargo.shop.food.commerce.service.FoodCatalogBindingService;
import ai.neargo.shop.food.commerce.service.impl.FoodCatalogPricePolicyServiceImpl;
import ai.neargo.shop.food.dto.MenuCatalogPriceContracts;
import ai.neargo.shop.food.dto.MenuContextInput;
import ai.neargo.shop.food.policy.MenuCatalogPricePolicy;
import ai.neargo.shop.food.service.MenuCatalogPriceService;
import ai.neargo.shop.spi.product.CatalogPriceSourceFacts;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

class FoodChannelPricePolicyTest {
    final FoodCatalogBindingFixtures f = new FoodCatalogBindingFixtures();
    final FoodCatalogBindingFixtures.MutableClock clock = new FoodCatalogBindingFixtures.MutableClock();
    final FoodCatalogBindingService bindings = mock(FoodCatalogBindingService.class);
    final MenuCatalogPriceService prices = mock(MenuCatalogPriceService.class);
    final FoodCatalogPricePolicyContracts.Query query = new FoodCatalogPricePolicyContracts.Query(
            FoodCatalogBindingFixtures.QUERY, FoodCatalogPricePolicy.REF);
    final FoodCatalogPricePolicyServiceImpl service =
            new FoodCatalogPricePolicyServiceImpl(bindings, prices, f.json, clock);

    FoodCatalogBindingContracts.Facts binding(String name, String reference) {
        return new FoodCatalogBindingContracts.Facts(query.binding(),
                f.menu(name, f.binding("TEST", "REMOTE_ACCOUNT", "REMOTE_STORE"), true, 25, reference),
                f.authority(), f.binding("TEST", "REMOTE_ACCOUNT", "REMOTE_STORE"),
                new MenuContextInput("CHANNEL", "DELIVERY", "B", reference),
                FoodCatalogBindingFixtures.NOW, FoodCatalogBindingFixtures.NOW.plusSeconds(20));
    }

    MenuCatalogPriceContracts.Facts originalPrices(String marketCode) {
        var at = FoodCatalogBindingFixtures.NOW;
        var request = new CatalogPriceSourcePort.Request("E", "S", List.of("SKU"));
        var market = new CatalogPriceSourcePort.Market(marketCode, "TWD", 2);
        var versions = new CatalogPriceSourcePort.Versions(0L, 0L, null, null);
        var price = new CatalogPriceSourcePort.Price("SKU", "G", "KNOWN", 500L, "ENTITY_SKU", versions, null);
        var row = new CatalogPriceSourcePort.Price("SKU", "G", "KNOWN", 500L, "ENTITY_SKU", versions,
                CatalogPriceSourceFacts.revision(request, market, price));
        var tax = new MerchantTaxSnapshot("MAIN", "E", "S", 1,
                MerchantTaxSnapshot.Source.MERCHANT_CUSTOM, "E", 1, 500, PriceTaxMode.EXCLUSIVE,
                new TaxSettingDefinition(true, 500, PriceTaxMode.EXCLUSIVE).snapshotHash("MERCHANT_CUSTOM:E", 1));
        var unsigned = new CatalogPriceSourcePort.Facts(1, "CATALOG_BASE", request, market,
                CatalogPriceSourceFacts.taxFact(tax), List.of(row), at, at.plusSeconds(30), null);
        var source = new CatalogPriceSourcePort.Facts(1, unsigned.purpose(), request, market, unsigned.tax(),
                unsigned.prices(), at, unsigned.validUntil(), CatalogPriceSourceFacts.receipt(unsigned));
        return MenuCatalogPricePolicy.seal(f.json, FoodCatalogPricePolicy.priceQuery(query),
                binding("Menu", FoodCatalogPricePolicy.REF).menuProof(), source, at);
    }

    void valid() {
        when(bindings.capture(query.binding())).thenReturn(binding("Menu", FoodCatalogPricePolicy.REF));
        when(prices.capture(FoodCatalogPricePolicy.priceQuery(query))).thenReturn(originalPrices("TW"));
    }

    @Test
    void preservesOriginalPricesAndTaxAndShortestEvidenceDeadline() {
        valid();
        var result = service.capture(query);
        FoodCatalogPricePolicy.validate(f.json, query, result, clock.instant());
        assertThat(result.mode()).isEqualTo(FoodCatalogPricePolicy.MODE);
        assertThat(result.prices().prices()).isEqualTo(originalPrices("TW").prices());
        assertThat(result.prices().prices().getFirst().unitPriceMinor()).isEqualTo(500L);
        assertThat(result.prices().productFacts().tax().rateBp()).isEqualTo(500);
        assertThat(result.validUntil()).isEqualTo(FoodCatalogBindingFixtures.NOW.plusSeconds(20));
    }

    @Test
    void rejectsUnknownPolicyBeforeReadingAnyOwner() {
        assertThatThrownBy(() -> service.capture(new FoodCatalogPricePolicyContracts.Query(query.binding(), "POLICY")))
                .isInstanceOf(BizException.class);
        verifyNoInteractions(bindings, prices);
    }

    @Test
    void refusesAFrozenContextThatDidNotSelectTheExplicitPolicy() {
        valid();
        when(bindings.capture(query.binding())).thenReturn(binding("Menu", "POLICY"));
        assertThatThrownBy(() -> service.capture(query)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsDifferentOriginalMarketsEvenWhenCurrencyMatches() {
        valid();
        when(prices.capture(FoodCatalogPricePolicy.priceQuery(query))).thenReturn(originalPrices("US"));
        assertThatThrownBy(() -> service.capture(query)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsMenuOrAuthorizationChangesAcrossPricing() {
        valid();
        when(bindings.capture(query.binding())).thenReturn(binding("Menu", FoodCatalogPricePolicy.REF),
                binding("Changed", FoodCatalogPricePolicy.REF));
        assertThatThrownBy(() -> service.capture(query)).isInstanceOf(BizException.class);
        var b = binding("Menu", FoodCatalogPricePolicy.REF);
        var a = b.authority();
        var changed = ChannelCatalogAuthorityPolicy.seal(new ChannelCatalogAuthorityContracts.Facts(
                1, a.purpose(), a.query(), a.provider(), a.profile(), a.account(), a.connection(),
                new ChannelCatalogAuthorityContracts.Grant("AUTH", 1L, 2L, "CATALOG", "c".repeat(64),
                        a.grant().expiresAt()), a.identity(), a.verifiedAt(), a.validUntil(), null));
        var second = new FoodCatalogBindingContracts.Facts(b.query(), b.menuProof(), changed,
                b.binding(), b.context(), b.verifiedAt(), b.validUntil());
        when(bindings.capture(query.binding())).thenReturn(b, second);
        assertThatThrownBy(() -> service.capture(query)).isInstanceOf(BizException.class);
    }

    @Test
    void checksumCannotAuthorizeAChangedPriceLineAndExpiredEvidenceCannotBeReturned() {
        valid();
        var original = originalPrices("TW");
        var row = original.prices().getFirst();
        var changed = new MenuCatalogPriceContracts.PriceLine(row.menuItemNo(), row.mainSkuNo(), row.groupNo(),
                row.choiceNo(), row.kind(), row.skuNo(), 1L, row.unitsPerPick(), row.sourceRevision());
        var unsigned = new MenuCatalogPriceContracts.Facts(1, original.query(), original.menuProof(),
                original.productFacts(), List.of(changed), original.verifiedAt(), original.validUntil(), null);
        var tampered = new MenuCatalogPriceContracts.Facts(1, unsigned.query(), unsigned.menuProof(),
                unsigned.productFacts(), unsigned.prices(), unsigned.verifiedAt(), unsigned.validUntil(),
                MenuCatalogPricePolicy.receipt(f.json, unsigned));
        when(prices.capture(FoodCatalogPricePolicy.priceQuery(query))).thenReturn(tampered);
        assertThatThrownBy(() -> service.capture(query)).isInstanceOf(IllegalStateException.class);
        when(prices.capture(FoodCatalogPricePolicy.priceQuery(query))).thenAnswer(c -> {
            clock.instant = FoodCatalogBindingFixtures.NOW.plusSeconds(21);
            return original;
        });
        assertThatThrownBy(() -> service.capture(query)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsOuterTransactionsWithoutOwnerCalls() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> service.capture(query)).isInstanceOf(IllegalStateException.class);
        } finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
        verifyNoInteractions(bindings, prices);
    }
}
