package ai.neargo.shop.merchant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.shop.merchant.port.StoreCardQueryPortImpl;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StoreCardQueryPortTest {
    @Test void delegatesOnlyBoundedCardsAndPreservesReadonlyUnknownHoursAndMissingStores() {
        var source = mock(StoreDirectoryPort.class);
        var card = new StoreDirectoryPort.StoreCard("S", "Store", "E", null, "READONLY", false,
                null, null, null, null, 0, 0);
        when(source.cards(List.of("S", "MISSING"))).thenReturn(Map.of("S", card));
        var port = new StoreCardQueryPortImpl(source);
        assertThat(port.cards(List.of("S", "MISSING"))).containsOnlyKeys("S");
        assertThat(port.cards(List.of("S", "MISSING")).get("S")).isEqualTo(card);
        assertThatThrownBy(() -> port.cards(List.of("S", "S"))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> port.cards(List.of())).isInstanceOf(RuntimeException.class);
    }
    @Test void refusesForeignMapKeysNonfiniteRatingsAndUnknownStatusInsteadOfInventingAuthority() {
        var source = mock(StoreDirectoryPort.class); var port = new StoreCardQueryPortImpl(source);
        when(source.cards(List.of("S"))).thenReturn(Map.of("OTHER", new StoreDirectoryPort.StoreCard("S", "Store", "E",
                null, "ACTIVE", true, "00:00-00:00", null, null, null, 0, 0)));
        assertThatThrownBy(() -> port.cards(List.of("S"))).isInstanceOf(IllegalStateException.class);
        for (var card : List.of(new StoreDirectoryPort.StoreCard("S", "Store", "E", null, "UNKNOWN", false,
                null, null, null, null, 0, 0), new StoreDirectoryPort.StoreCard("S", "Store", "E", null,
                        "ACTIVE", false,
                null, null, null, null, Double.NaN, 0))) {
            when(source.cards(List.of("S"))).thenReturn(Map.of("S", card));
            assertThatThrownBy(() -> port.cards(List.of("S"))).isInstanceOf(IllegalStateException.class);
        }
    }
}
