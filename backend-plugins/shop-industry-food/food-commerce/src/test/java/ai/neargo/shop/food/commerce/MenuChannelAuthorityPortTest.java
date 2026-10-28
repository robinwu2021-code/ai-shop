package ai.neargo.shop.food.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.neargo.shop.commerce.dto.ChannelCatalogAuthorityContracts.IdentityQuery;
import ai.neargo.shop.commerce.service.ChannelCatalogAuthorityService;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.commerce.policy.FoodCatalogPricePolicy;
import ai.neargo.shop.food.commerce.port.MenuChannelAuthorityPortImpl;
import ai.neargo.shop.food.dto.MenuChannelBindingVO;
import ai.neargo.shop.food.service.MenuChannelBindingService;
import ai.neargo.shop.spi.product.CatalogPriceSourceFacts;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort.Market;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort.MarketFacts;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort.MarketScope;

import org.junit.jupiter.api.Test;

class MenuChannelAuthorityPortTest {
    final FoodCatalogBindingFixtures f = new FoodCatalogBindingFixtures();
    final FoodCatalogBindingFixtures.MutableClock clock = new FoodCatalogBindingFixtures.MutableClock();
    final MenuChannelBindingService bindings = mock(MenuChannelBindingService.class);
    final ChannelCatalogAuthorityService authorities = mock(ChannelCatalogAuthorityService.class);
    final CatalogPriceSourcePort products = mock(CatalogPriceSourcePort.class);
    final MenuChannelAuthorityPortImpl port = new MenuChannelAuthorityPortImpl(bindings, authorities, products, clock);
    final IdentityQuery query = new IdentityQuery("E", "S", "TEST", "REMOTE_ACCOUNT", "REMOTE_STORE");
    final MarketScope scope = new MarketScope("E", "S");

    MarketFacts market(String code) {
        return CatalogPriceSourceFacts.sealMarket(new MarketFacts(1, "CATALOG_MARKET", scope,
                new Market(code, "TWD", 2), FoodCatalogBindingFixtures.NOW,
                FoodCatalogBindingFixtures.NOW.plusSeconds(15), null));
    }

    void valid() {
        when(authorities.resolve(query)).thenReturn(f.authority());
        when(bindings.getBinding("E", "S", "B")).thenReturn(f.binding("TEST", "REMOTE_ACCOUNT", "REMOTE_STORE"));
        when(products.market(scope)).thenReturn(market("TW"));
    }

    @Test
    void authorizesExactOriginalIdentityAndPricePolicyWithoutAnyPublishedMenu() {
        valid();
        assertThat(port.authorized("E", "S", "TEST", "REMOTE_ACCOUNT", "REMOTE_STORE")).isTrue();
        assertThat(port.pricePolicyAuthorized("E", "S", "B", FoodCatalogPricePolicy.REF)).isTrue();
    }

    @Test
    void unsupportedPoliciesNeverConsultOwnersAndDraftBindingsCannotUsePrices() {
        assertThat(port.pricePolicyAuthorized("E", "S", "B", "POLICY")).isFalse();
        verifyNoInteractions(bindings, authorities, products);
        when(bindings.getBinding("E", "S", "B"))
                .thenReturn(new MenuChannelBindingVO("B", "M", "S", "TEST", "REMOTE_ACCOUNT", "REMOTE_STORE",
                        "DRAFT", 0L));
        assertThat(port.pricePolicyAuthorized("E", "S", "B", FoodCatalogPricePolicy.REF)).isFalse();
        verifyNoInteractions(authorities, products);
    }

    @Test
    void rejectsChangedBindingsOrMismatchedMarkets() {
        valid();
        when(products.market(scope)).thenReturn(market("US"));
        assertThat(port.pricePolicyAuthorized("E", "S", "B", FoodCatalogPricePolicy.REF)).isFalse();
        when(products.market(scope)).thenReturn(market("TW"));
        when(bindings.getBinding("E", "S", "B")).thenReturn(f.binding("TEST", "REMOTE_ACCOUNT", "REMOTE_STORE"),
                new MenuChannelBindingVO("B", "M", "S", "TEST", "REMOTE_ACCOUNT", "REMOTE_STORE", "ACTIVE", 1L));
        assertThat(port.pricePolicyAuthorized("E", "S", "B", FoodCatalogPricePolicy.REF)).isFalse();
    }

    @Test
    void rejectsExpiredOrForgedOriginalProofsAndPreservesOwnerDenial() {
        valid();
        when(products.market(scope)).thenAnswer(c -> {
            clock.instant = clock.instant.plusSeconds(21);
            return market("TW");
        });
        assertThatThrownBy(() -> port.pricePolicyAuthorized("E", "S", "B", FoodCatalogPricePolicy.REF))
                .isInstanceOf(RuntimeException.class);
        var denied = BizException.of(ErrorCode.FORBIDDEN);
        when(authorities.resolve(query)).thenThrow(denied);
        assertThatThrownBy(() -> port.authorized("E", "S", "TEST", "REMOTE_ACCOUNT", "REMOTE_STORE"))
                .isSameAs(denied);
    }
}
