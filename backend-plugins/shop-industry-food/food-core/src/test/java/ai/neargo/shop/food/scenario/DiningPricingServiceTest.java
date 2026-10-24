package ai.neargo.shop.food.scenario;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.food.dto.CartAccess;
import ai.neargo.shop.food.dto.CartResultVO;
import ai.neargo.shop.food.dto.CartVO;
import ai.neargo.shop.food.dto.DiningQuoteReq;
import ai.neargo.shop.food.dto.DiningQuoteConfirmReq;
import ai.neargo.shop.food.service.CheckCartService;
import ai.neargo.shop.food.service.impl.DiningPricingServiceImpl;
import ai.neargo.shop.spi.trade.PricingCheckoutPort;
import ai.neargo.shop.spi.trade.PricingCheckoutPort.Selection;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Credentials;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Scope;
import ai.neargo.shop.spi.trade.PricingSourceRef;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DiningPricingServiceTest {
    private final CheckCartService carts = mock(CheckCartService.class);
    private final PricingCheckoutPort core = mock(PricingCheckoutPort.class);
    private final DiningPricingServiceImpl service = new DiningPricingServiceImpl(carts, core);
    private final CartAccess guest = new CartAccess("SESSION", "TOKEN", null);

    @Test void staffScopeAndOriginalCartVersionReachSharedCore() {
        when(carts.getCart("E", "S", "C")).thenReturn(new CartResultVO(cart("S", 4, "OPEN")));
        service.staffQuote("E", "S", "ACTOR", new DiningQuoteReq("C", 4, null), "KEY");
        verify(core).quoteStaff(new Scope("MAIN", "E", "S"), "ACTOR", source(), Selection.none(), "KEY");
    }
    @Test void customerCannotQuoteAnotherStoreThroughPath() {
        when(carts.customerGetCart("C", guest)).thenReturn(new CartResultVO(cart("OTHER", 4, "OPEN")));
        assertThatThrownBy(() -> service.customerQuote("S", guest, new DiningQuoteReq("C", 4, null), "KEY"))
                .isInstanceOf(BizException.class);
        verifyNoInteractions(core);
    }
    @Test void staleAndSubmittingCartsNeverEnterPricing() {
        when(carts.getCart("E", "S", "C")).thenReturn(new CartResultVO(cart("S", 5, "OPEN")));
        assertThatThrownBy(() -> service.staffQuote("E", "S", "A", new DiningQuoteReq("C", 4, null), "KEY"))
                .isInstanceOf(BizException.class);
        when(carts.getCart("E", "S", "C")).thenReturn(new CartResultVO(cart("S", 4, "SUBMITTING")));
        assertThatThrownBy(() -> service.staffQuote("E", "S", "A", new DiningQuoteReq("C", 4, null), "KEY"))
                .isInstanceOf(BizException.class);
        verifyNoInteractions(core);
    }
    @Test void guestCredentialsAreReauthorizedAndForwardedWithoutInventingBuyer() {
        when(carts.customerGetCart("C", guest)).thenReturn(new CartResultVO(cart("S", 4, "OPEN")));
        service.customerQuote("S", guest, new DiningQuoteReq("C", 4, Selection.none()), "KEY");
        verify(core).quoteCustomer(source(), new Credentials("SESSION", "TOKEN", null), Selection.none(), "KEY");
    }
    @Test void finalConfirmationUsesSameQuoteDigestAndCoreRevalidation() {
        when(carts.customerGetCart("C", guest)).thenReturn(new CartResultVO(cart("S", 4, "OPEN")));
        service.customerConfirm(guest, "Q", new DiningQuoteConfirmReq("C", 4, "a".repeat(64)));
        verify(core).revalidateCustomer(source(), new Credentials("SESSION", "TOKEN", null), "Q", "a".repeat(64));
    }
    @Test void revokedSourceAccessPreventsEvenOldQuoteLookup() {
        when(carts.customerGetCart("C", guest)).thenThrow(BizException.of(ai.neargo.shop.common.ErrorCode.FORBIDDEN));
        assertThatThrownBy(() -> service.customerGet(guest, "C", 4, "Q")).isInstanceOf(BizException.class);
        verifyNoInteractions(core);
    }
    private static PricingSourceRef source() { return new PricingSourceRef("FOOD", "CART", "C", 4, null, null); }
    private static CartVO cart(String store, long version, String status) {
        return new CartVO("C", store, "CHECK", "SESSION", null, status, "H5", "DINE_IN", null,
                version, List.of());
    }
}
