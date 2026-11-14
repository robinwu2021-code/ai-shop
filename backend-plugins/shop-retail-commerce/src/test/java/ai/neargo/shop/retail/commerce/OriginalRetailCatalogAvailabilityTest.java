package ai.neargo.shop.retail.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.shop.retail.commerce.dto.RetailSelection;
import ai.neargo.shop.retail.commerce.port.OriginalRetailCatalogAvailability;
import ai.neargo.shop.spi.pay.MarketPort;
import ai.neargo.shop.spi.product.CatalogPriceSourceFacts;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort;
import ai.neargo.shop.spi.user.StoreCardQueryPort;
import ai.neargo.shop.spi.user.StoreDirectoryPort;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

class OriginalRetailCatalogAvailabilityTest {
    final Instant now = Instant.parse("2026-10-29T00:00:00Z");
    final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    final StoreCardQueryPort stores = mock(StoreCardQueryPort.class);
    final CatalogPriceSourcePort prices = mock(CatalogPriceSourcePort.class);
    final MarketPort markets = mock(MarketPort.class);
    final OriginalRetailCatalogAvailability calendar =
            new OriginalRetailCatalogAvailability(stores, prices, markets, clock);
    final List<RetailSelection> selections =
            List.of(
                    new RetailSelection(
                            "E",
                            "S",
                            "SEL",
                            "R",
                            "C",
                            "CR",
                            List.of(
                                    new RetailSelection.Entry(
                                            "G", 7, "a".repeat(64), List.of("SKU")))));

    @BeforeEach
    void originals() {
        market("CN", "CNY", "Asia/Shanghai");
        hours("ACTIVE", "09:00-18:00");
    }

    void market(String market, String currency, String zone) {
        when(prices.market(any()))
                .thenAnswer(
                        call ->
                                CatalogPriceSourceFacts.sealMarket(
                                        new CatalogPriceSourcePort.MarketFacts(
                                                1,
                                                "CATALOG_MARKET",
                                                call.getArgument(0),
                                                new CatalogPriceSourcePort.Market(
                                                        market, currency, 2),
                                                now,
                                                now.plusSeconds(20),
                                                null)));
        when(markets.find(market))
                .thenReturn(
                        Optional.of(
                                new MarketPort.MarketBrief(
                                        market, market, currency, 2, zone, 1, true)));
    }

    void hours(String status, String text) {
        when(stores.cards(List.of("S")))
                .thenReturn(
                        Map.of(
                                "S",
                                new StoreDirectoryPort.StoreCard(
                                        "S",
                                        "Original store",
                                        "E",
                                        null,
                                        status,
                                        true,
                                        text,
                                        null,
                                        null,
                                        null,
                                        0,
                                        0)));
    }

    @Test
    void actualHoursAndMarketTimezoneProduceFiniteWindows() {
        var facts = calendar.capture("E", "S", selections);
        var windows = facts.availability().sources().getFirst().effectiveWindows();
        assertThat(windows).hasSize(7);
        assertThat(windows.getFirst().fromInclusive())
                .isEqualTo(Instant.parse("2026-10-29T01:00:00Z"));
        assertThat(windows.getFirst().toExclusive())
                .isEqualTo(Instant.parse("2026-10-29T10:00:00Z"));
        assertThat(facts.validUntil()).isEqualTo(now.plusSeconds(20));
    }

    @Test
    void unknownHoursCannotBecomeAlwaysOpen() {
        hours("ACTIVE", "call for opening hours");
        assertThatThrownBy(() -> calendar.capture("E", "S", selections))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void missingTimezoneCannotFallbackToShanghaiOrSystemDefault() {
        when(markets.find("CN")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> calendar.capture("E", "S", selections))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void readonlyOriginalStoreIsClosedWithoutInventingHours() {
        hours("READONLY", null);
        assertThat(
                        calendar.capture("E", "S", selections)
                                .availability()
                                .sources()
                                .getFirst()
                                .effectiveWindows())
                .isEmpty();
    }

    @Test
    void explicitOriginalEqualBoundariesMeanFullDayAccordingToOriginalParser() {
        hours("ACTIVE", "00:00-00:00");
        var facts = calendar.capture("E", "S", selections);
        var windows = facts.availability().sources().getFirst().effectiveWindows();
        assertThat(windows).hasSize(1);
        assertThat(windows.getFirst().fromInclusive()).isEqualTo(now);
        assertThat(windows.getFirst().toExclusive()).isEqualTo(now.plus(Duration.ofDays(7)));
    }

    @Test
    void overnightWindowClipsAtUtcHorizonRatherThanDroppingPreviousLocalDay() {
        hours("ACTIVE", "22:00-10:00");
        var windows =
                calendar.capture("E", "S", selections)
                        .availability()
                        .sources()
                        .getFirst()
                        .effectiveWindows();
        assertThat(windows.getFirst().fromInclusive()).isEqualTo(now);
        assertThat(windows.getFirst().toExclusive())
                .isEqualTo(Instant.parse("2026-10-29T02:00:00Z"));
    }

    @Test
    void ambiguousDstBoundaryFailsClosed() {
        market("US", "USD", "America/New_York");
        hours("ACTIVE", "01:30-03:00");
        assertThatThrownBy(() -> calendar.capture("E", "S", selections))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void foreignStoreOwnerCannotContributeWindows() {
        when(stores.cards(List.of("S")))
                .thenReturn(
                        Map.of(
                                "S",
                                new StoreDirectoryPort.StoreCard(
                                        "S",
                                        "Other",
                                        "OTHER",
                                        null,
                                        "ACTIVE",
                                        true,
                                        "09:00-18:00",
                                        null,
                                        null,
                                        null,
                                        0,
                                        0)));
        assertThatThrownBy(() -> calendar.capture("E", "S", selections))
                .isInstanceOf(IllegalStateException.class);
    }
}
