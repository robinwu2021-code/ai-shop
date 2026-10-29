package ai.neargo.shop.retail.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.shop.retail.commerce.port.OriginalRetailCatalogAvailability;
import ai.neargo.shop.spi.commerce.ChannelAvailabilitySourcePort.Kind;
import ai.neargo.shop.spi.commerce.ChannelAvailabilitySourcePort.Query;
import ai.neargo.shop.spi.commerce.ChannelAvailabilitySourcePort.State;
import ai.neargo.shop.spi.commerce.ChannelAvailabilitySourcePort.StoreMode;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;
import ai.neargo.shop.spi.pay.MarketPort;
import ai.neargo.shop.spi.product.CatalogPriceSourceFacts;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort;
import ai.neargo.shop.spi.user.StoreCardQueryPort;
import ai.neargo.shop.spi.user.StoreDirectoryPort.StoreCard;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

class RetailStoreBusinessAvailabilityTest {
    final Instant now = Instant.parse("2026-10-29T01:00:00Z");
    final StoreCardQueryPort stores = mock(StoreCardQueryPort.class);
    final CatalogPriceSourcePort prices = mock(CatalogPriceSourcePort.class);
    final MarketPort markets = mock(MarketPort.class);
    final OriginalRetailCatalogAvailability source =
            new OriginalRetailCatalogAvailability(
                    stores, prices, markets, Clock.fixed(now, ZoneOffset.UTC));

    @BeforeEach
    void owners() {
        card("ACTIVE", "09:00-18:00");
        when(prices.market(any()))
                .thenAnswer(
                        a ->
                                CatalogPriceSourceFacts.sealMarket(
                                        new CatalogPriceSourcePort.MarketFacts(
                                                1,
                                                "CATALOG_MARKET",
                                                a.getArgument(0),
                                                new CatalogPriceSourcePort.Market("CN", "CNY", 2),
                                                now,
                                                now.plusSeconds(20),
                                                null)));
        when(markets.find("CN"))
                .thenReturn(
                        Optional.of(
                                new MarketPort.MarketBrief(
                                        "CN", "CN", "CNY", 2, "Asia/Shanghai", 1, true)));
    }

    void card(String status, String hours) {
        when(stores.cards(List.of("S")))
                .thenReturn(
                        Map.of(
                                "S",
                                new StoreCard(
                                        "S",
                                        "Original store",
                                        "E",
                                        null,
                                        status,
                                        true,
                                        hours,
                                        null,
                                        null,
                                        null,
                                        0,
                                        0)));
    }

    Query query(StoreMode mode) {
        return new Query("E", "S", Kind.STORE, "S", Owner.RETAIL, mode);
    }

    @Test
    void realStoreHoursAndActualMarketZoneNeedNoSyntheticSelection() {
        var facts = source.capture(query(StoreMode.PHYSICAL_OPEN));
        assertThat(facts.data().state()).isEqualTo(State.OPEN);
        assertThat(facts.data().nextChangeAt()).isEqualTo(Instant.parse("2026-10-29T10:00:00Z"));
        assertThat(facts.validUntil()).isEqualTo(now.plusSeconds(20));
        assertThat(facts.data().quantity()).isNull();
    }

    @Test
    void retailHasNoAuthorityToInventAnAcceptingOrderState() {
        var facts = source.capture(query(StoreMode.ACCEPTING_ORDERS));
        assertThat(facts.data().state()).isEqualTo(State.UNSUPPORTED);
        assertThat(facts.originalReceiptRef()).isNull();
    }

    @Test
    void unknownHoursAndMissingTimezoneNeverAcquireAnOpenOrStockZeroMeaning() {
        card("ACTIVE", "Call the store");
        assertThat(source.capture(query(StoreMode.PHYSICAL_OPEN)).data().state())
                .isEqualTo(State.UNKNOWN);
        card("READONLY", null);
        var facts = source.capture(query(StoreMode.PHYSICAL_OPEN));
        assertThat(facts.data().state()).isEqualTo(State.SUSPENDED);
        assertThat(facts.data().quantity()).isNull();
        when(markets.find("CN")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> source.capture(query(StoreMode.PHYSICAL_OPEN)))
                .isInstanceOf(IllegalStateException.class);
    }
}
