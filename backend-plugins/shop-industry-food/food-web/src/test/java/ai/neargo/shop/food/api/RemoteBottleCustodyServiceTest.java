package ai.neargo.shop.food.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.neargo.shop.food.dto.BottleCustodyContracts.Custody;
import ai.neargo.shop.food.dto.BottleCustodyContracts.Deposit;
import ai.neargo.shop.food.dto.BottleCustodyContracts.Movement;
import ai.neargo.shop.food.dto.BottleCustodyContracts.Result;
import ai.neargo.shop.food.foodclient.RemoteBottleCustodyService;
import ai.neargo.shop.food.spi.BottleCustodyInternalApi;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The BFF accepts original owner receipts only, including when a remote deployment is misrouted.
 */
class RemoteBottleCustodyServiceTest {
    static final Instant NOW = Instant.parse("2026-10-29T00:00:00Z");

    Custody custody(String store) {
        return new Custody(
                "BC",
                "E",
                store,
                "U",
                "SKU",
                BigDecimal.ONE,
                BigDecimal.ONE,
                "PIECE",
                NOW.plusSeconds(3600),
                "OPEN",
                0);
    }

    @Test
    void wrongStoreAndMissingOwnerReceiptNeverBecomeACustodyResult() {
        var api = mock(BottleCustodyInternalApi.class);
        var service = new RemoteBottleCustodyService(api);
        when(api.get(any())).thenReturn(custody("OTHER"));
        assertThatThrownBy(() -> service.get("E", "S", "BC"))
                .isInstanceOf(IllegalStateException.class);
        when(api.get(any())).thenReturn(null);
        assertThatThrownBy(() -> service.get("E", "S", "BC"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void originalDepositKeyScopeAndReceiptArePreservedAcrossTransport() {
        var api = mock(BottleCustodyInternalApi.class);
        var service = new RemoteBottleCustodyService(api);
        var request =
                new Deposit(
                        "U",
                        "SKU",
                        BigDecimal.ONE,
                        "PIECE",
                        NOW.plusSeconds(3600),
                        "Actual deposit",
                        "PAPER");
        var original =
                new Result(
                        custody("S"),
                        new Movement(
                                "M",
                                "BC",
                                "DEPOSIT",
                                BigDecimal.ONE,
                                BigDecimal.ONE,
                                "PIECE",
                                "STAFF",
                                "U",
                                "Actual deposit",
                                NOW));
        when(api.deposit(any())).thenReturn(original);
        assertThat(service.deposit("E", "S", "STAFF", request, "ORIGINAL_KEY")).isEqualTo(original);
        verify(api)
                .deposit(
                        new BottleCustodyInternalApi.DepositCommand(
                                "E", "S", "STAFF", request, "ORIGINAL_KEY"));
    }
}
