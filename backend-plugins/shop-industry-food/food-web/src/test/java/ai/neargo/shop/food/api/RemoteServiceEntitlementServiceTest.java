package ai.neargo.shop.food.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.neargo.shop.food.dto.ServiceEntitlementContracts.Entitlement;
import ai.neargo.shop.food.dto.ServiceEntitlementContracts.Grant;
import ai.neargo.shop.food.dto.ServiceEntitlementContracts.QuoteRef;
import ai.neargo.shop.food.foodclient.RemoteServiceEntitlementService;
import ai.neargo.shop.food.spi.ServiceEntitlementInternalApi;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

class RemoteServiceEntitlementServiceTest {
    Entitlement entitlement(String form) {
        return new Entitlement(
                "ENTITLEMENT",
                form,
                "ATTENDEE",
                "GOODS",
                "SKU",
                new BigDecimal("5"),
                BigDecimal.ZERO,
                "PIECE",
                Instant.parse("2026-10-29T00:00:00Z"),
                Instant.parse("2026-10-29T01:00:00Z"),
                "ORIGINAL_QUOTE",
                "a".repeat(64),
                0);
    }

    @Test
    void originalScopeFormVersionQuoteAndPermanentKeyArePreserved() {
        var api = mock(ServiceEntitlementInternalApi.class);
        var service = new RemoteServiceEntitlementService(api);
        var request =
                new Grant(
                        3,
                        "ATTENDEE",
                        "ITEM",
                        "GOODS",
                        "SKU",
                        new BigDecimal("5"),
                        new QuoteRef("CART", 2, "ORIGINAL_QUOTE", "b".repeat(64)),
                        "Actual operational grant");
        when(api.grant(any())).thenReturn(entitlement("FORM"));
        assertThat(service.grant("E", "S", "ACTOR", "FORM", request, "PERMANENT_KEY"))
                .isEqualTo(entitlement("FORM"));
        verify(api)
                .grant(
                        new ServiceEntitlementInternalApi.GrantCommand(
                                "E", "S", "ACTOR", "FORM", request, "PERMANENT_KEY"));
    }

    @Test
    void wrongFormOrMissingOriginalOwnerReceiptDoesNotBecomeOperationalAllowance() {
        var api = mock(ServiceEntitlementInternalApi.class);
        var service = new RemoteServiceEntitlementService(api);
        when(api.list(any())).thenReturn(List.of(entitlement("OTHER_FORM")));
        assertThatThrownBy(() -> service.entitlements("E", "S", "FORM"))
                .isInstanceOf(IllegalStateException.class);
        when(api.list(any())).thenReturn(null);
        assertThatThrownBy(() -> service.entitlements("E", "S", "FORM"))
                .isInstanceOf(IllegalStateException.class);
    }
}
