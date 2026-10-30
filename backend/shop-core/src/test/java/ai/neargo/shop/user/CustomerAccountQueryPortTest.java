package ai.neargo.shop.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.neargo.shop.spi.user.CustomerAccountQueryPort.Query;
import ai.neargo.shop.spi.user.StoreCardQueryPort;
import ai.neargo.shop.spi.user.StoreDirectoryPort.StoreCard;
import ai.neargo.shop.spi.user.UserQueryPort;
import ai.neargo.shop.user.port.CustomerAccountQueryPortImpl;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Account facts come from existing owners; absence and infrastructure failure remain different
 * outcomes.
 */
class CustomerAccountQueryPortTest {
    StoreCard card(String entity) {
        return new StoreCard(
                "S",
                "Original store",
                entity,
                null,
                "ACTIVE",
                true,
                "09:00-22:00",
                null,
                null,
                null,
                0,
                0);
    }

    @Test
    void originalAccountExistenceDoesNotDiscloseContactOrCreateAnAccount() {
        var stores = mock(StoreCardQueryPort.class);
        var users = mock(UserQueryPort.class);
        when(stores.cards(List.of("S"))).thenReturn(Map.of("S", card("E")));
        when(users.find("U"))
                .thenReturn(
                        Optional.of(new UserQueryPort.UserBrief("U", "Customer", "1234", null)));
        var query = new Query("E", "S", "U");
        var result = new CustomerAccountQueryPortImpl(stores, users).find(query);
        assertThat(result.query()).isEqualTo(query);
        assertThat(result.exists()).isTrue();
        assertThat(result.getClass().getRecordComponents()).hasSize(2);
    }

    @Test
    void storeOwnershipIsCheckedBeforeOriginalCustomerRead() {
        var stores = mock(StoreCardQueryPort.class);
        var users = mock(UserQueryPort.class);
        when(stores.cards(List.of("S"))).thenReturn(Map.of("S", card("OTHER")));
        assertThatThrownBy(
                        () ->
                                new CustomerAccountQueryPortImpl(stores, users)
                                        .find(new Query("E", "S", "U")))
                .isInstanceOf(RuntimeException.class);
        verifyNoInteractions(users);
    }

    @Test
    void missingAccountIsFalseButUnavailableOwnerNeverBecomesFalse() {
        var stores = mock(StoreCardQueryPort.class);
        var users = mock(UserQueryPort.class);
        when(stores.cards(List.of("S"))).thenReturn(Map.of("S", card("E")));
        var port = new CustomerAccountQueryPortImpl(stores, users);
        when(users.find("MISSING")).thenReturn(Optional.empty());
        assertThat(port.find(new Query("E", "S", "MISSING")).exists()).isFalse();
        when(users.find("U")).thenThrow(new IllegalStateException("Original owner unavailable"));
        assertThatThrownBy(() -> port.find(new Query("E", "S", "U")))
                .isInstanceOf(IllegalStateException.class);
    }
}
