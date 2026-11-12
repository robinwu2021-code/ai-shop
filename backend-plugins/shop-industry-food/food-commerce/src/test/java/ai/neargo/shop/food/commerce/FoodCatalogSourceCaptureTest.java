package ai.neargo.shop.food.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.neargo.shop.commerce.service.ChannelCatalogAuthorityService;
import ai.neargo.shop.common.pricing.PriceTaxMode;
import ai.neargo.shop.common.pricing.TaxSettingDefinition;
import ai.neargo.shop.food.commerce.port.FoodCatalogSourcePortImpl;
import ai.neargo.shop.food.commerce.service.FoodCatalogPricePolicyService;
import ai.neargo.shop.food.dto.MenuChannelBindingVO;
import ai.neargo.shop.food.dto.MenuSourceContracts;
import ai.neargo.shop.food.service.MenuChannelBindingService;
import ai.neargo.shop.food.service.ConfigRevisionService;
import ai.neargo.shop.food.service.StoreCalendarSourceService;
import ai.neargo.shop.spi.commerce.ChannelCatalogSourcePort.CaptureCommand;
import ai.neargo.shop.spi.commerce.ChannelCatalogSourcePort.ValidatedSource;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Status;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Validation;
import ai.neargo.shop.spi.media.MediaPublicSourcePort;
import ai.neargo.shop.spi.product.CatalogPriceSourceFacts;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Channel capture against one connection: a menu not (or no longer) bound to it sells nothing there. */
class FoodCatalogSourceCaptureTest {
    final FoodCatalogBindingFixtures f = new FoodCatalogBindingFixtures();
    final FoodCatalogAvailabilityTest calendars = new FoodCatalogAvailabilityTest();
    final ConfigRevisionService menus = mock(ConfigRevisionService.class);
    final ChannelCatalogAuthorityService authorities = mock(ChannelCatalogAuthorityService.class);
    final FoodCatalogPricePolicyService prices = mock(FoodCatalogPricePolicyService.class);
    final StoreCalendarSourceService calendar = mock(StoreCalendarSourceService.class);
    final CatalogPriceSourcePort scopes = mock(CatalogPriceSourcePort.class);
    final MenuChannelBindingService bindings = mock(MenuChannelBindingService.class);
    final Clock clock = Clock.fixed(FoodCatalogBindingFixtures.NOW, ZoneOffset.UTC);

    FoodCatalogSourcePortImpl port(MenuSourceContracts.Proof menu) {
        var now = FoodCatalogBindingFixtures.NOW;
        when(menus.verifyMenuResource(any())).thenReturn(menu);
        when(authorities.capture(any())).thenReturn(f.authority());
        when(calendar.capture(any()))
                .thenReturn(calendars.calendar(now, "00:00-00:00", "UTC", 0, List.of(), List.of()));
        var tax = new CatalogPriceSourcePort.TaxFact("MERCHANT_CUSTOM", "E", 1L, 1L, 500, "EXCLUSIVE",
                new TaxSettingDefinition(true, 500, PriceTaxMode.EXCLUSIVE).snapshotHash("MERCHANT_CUSTOM:E", 1));
        when(scopes.scope(any())).thenReturn(CatalogPriceSourceFacts.sealScope(
                new CatalogPriceSourcePort.ScopeFacts(1, "CATALOG_SCOPE",
                        new CatalogPriceSourcePort.MarketScope("E", "S"),
                        new CatalogPriceSourcePort.Market("TW", "TWD", 2), tax, now, now.plusSeconds(25),
                        null)));
        live("ACTIVE");
        var port = new FoodCatalogSourcePortImpl(menus, authorities, prices, calendar,
                mock(GoodsCatalogQueryPort.class), mock(MediaPublicSourcePort.class), bindings, f.json, clock);
        port.withdrawalSupport(true, () -> scopes, () -> null);
        return port;
    }

    void live(String status) {
        when(bindings.getBinding(eq("E"), eq("S"), eq("B"))).thenReturn(new MenuChannelBindingVO(
                "B", "M", "S", "TEST", "REMOTE_ACCOUNT", "REMOTE_STORE", status, 1L));
    }

    CaptureCommand command(MenuSourceContracts.Proof menu) {
        var proof = new Validation(Status.VALID, "M", "MR", menu.publication().publication().contentHash(),
                FoodCatalogBindingFixtures.NOW.plusSeconds(20), "RECEIPT", null, null);
        return new CaptureCommand("E", "S", Owner.FOOD, "CON", "CR",
                List.of(new ValidatedSource(Owner.FOOD, "M", "MR", 0, proof)), null);
    }

    @Test void menuWhoseFrozenBindingTargetsAnotherStoreIsCapturedClosedAndEmptyForThisConnection() {
        var menu = f.menu("Menu", f.binding("TEST", "REMOTE_ACCOUNT", "ANOTHER_STORE"), true, 25);
        var document = port(menu).capture(command(menu));
        assertThat(document.details().offers()).isEmpty();
        assertThat(document.availability().sources()).singleElement()
                .satisfies(s -> assertThat(s.effectiveWindows()).isEmpty());
        verify(prices, never()).capture(any());
    }

    @Test void menuStillBoundToThisConnectionKeepsGoingThroughItsChannelPricePolicy() {
        var menu = f.menu();
        assertThatThrownBy(() -> port(menu).capture(command(menu))).isInstanceOf(RuntimeException.class);
        verify(prices).capture(any());
    }

    @Test void bindingDisabledAfterPublicationDelistsTheMenuFromThisConnectionWithoutWaitingForRepublish() {
        var menu = f.menu();
        var port = port(menu);
        live("DISABLED");
        var document = port.capture(command(menu));
        assertThat(document.details().offers()).isEmpty();
        assertThat(document.availability().sources()).singleElement()
                .satisfies(s -> assertThat(s.effectiveWindows()).isEmpty());
        verify(prices, never()).capture(any());
    }
}
