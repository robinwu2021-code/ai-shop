package ai.neargo.shop.commerce.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.neargo.shop.spi.commerce.ChannelAvailabilitySourcePort.Data;
import ai.neargo.shop.spi.commerce.ChannelAvailabilitySourcePort.Facts;
import ai.neargo.shop.spi.commerce.ChannelAvailabilitySourcePort.Kind;
import ai.neargo.shop.spi.commerce.ChannelAvailabilitySourcePort.Query;
import ai.neargo.shop.spi.commerce.ChannelAvailabilitySourcePort.State;
import ai.neargo.shop.spi.commerce.ChannelAvailabilitySourcePort.StoreMode;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;
import ai.neargo.shop.spi.commerce.StoreBusinessAvailabilityInternalApi;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

class RemoteStoreBusinessAvailabilitySourceTest {
    @Test
    void originalOwnerModeAndStoreAreBoundAndNoCrossIndustryFallbackExists() {
        var now = Instant.parse("2026-10-29T00:00:00Z");
        var api = mock(StoreBusinessAvailabilityInternalApi.class);
        var remote =
                new RemoteStoreBusinessAvailabilitySource(
                        Owner.FOOD, () -> api, Clock.fixed(now, ZoneOffset.UTC));
        var query = new Query("E", "S", Kind.STORE, "S", Owner.FOOD, StoreMode.ACCEPTING_ORDERS);
        var original =
                new Facts(
                        new Data(
                                query,
                                State.SUSPENDED,
                                null,
                                null,
                                "ORIGINAL_POLICY",
                                now.plusSeconds(10)),
                        now,
                        now.plusSeconds(10),
                        "ORIGINAL_CALENDAR");
        when(api.capture(anyString(), any())).thenReturn(original);
        assertThat(remote.capture(query)).isEqualTo(original);
        verify(api).capture("food", query);
        assertThatThrownBy(
                        () ->
                                remote.capture(
                                        new Query(
                                                "E",
                                                "S",
                                                Kind.STORE,
                                                "S",
                                                Owner.RETAIL,
                                                StoreMode.ACCEPTING_ORDERS)))
                .isInstanceOf(IllegalStateException.class);
        var foreign =
                new Query(
                        "E", "OTHER", Kind.STORE, "OTHER", Owner.FOOD, StoreMode.ACCEPTING_ORDERS);
        when(api.capture(anyString(), any()))
                .thenReturn(
                        new Facts(
                                new Data(foreign, State.OPEN, null, null, "FOREIGN"),
                                now,
                                now.plusSeconds(20),
                                "WRONG_STORE"));
        assertThatThrownBy(() -> remote.capture(query)).isInstanceOf(IllegalStateException.class);
    }
}
