package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.common.data.scope.DataScopeSpec;
import ai.neargo.shop.auth.ScopeDim;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.food.dto.CartAccess;
import ai.neargo.shop.food.dto.CartVersionReq;
import ai.neargo.shop.food.dto.CreateCustomerCartReq;
import ai.neargo.shop.food.dto.OrderLineInput;
import ai.neargo.shop.food.dto.ReplaceCartLineReq;
import java.math.BigDecimal;
import java.util.List;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The real cart authority reads submitted H5 history from original buyer/binding facts, without reviving credentials. */
class ClosedCartBuyerReadTest {
    @Test void expiredH5ReadRequiresExactOriginalBuyerAndBoundStoreBatchAndNeverGrantsWriteOrGuestAccess() {
        CartServiceTest fixture = new CartServiceTest();
        try {
            fixture.run(state -> {
                var buyer = new CartAccess(null, null, "BUYER");
                var cart = state.service().customerCreateCart("A", buyer,
                        new CreateCustomerCartReq(null, null, "PICKUP", null), "closed-source").cart();
                assertThat(cart.buyerNo()).isNull(); // Public views intentionally redact the original identity.
                state.f().sql().update("UPDATE fnb_check_cart SET status='CLOSED' WHERE cart_no=?", cart.cartNo());
                state.f().sql().update("UPDATE fnb_guest_session SET expires_at=? WHERE session_no=?",
                        LocalDateTime.ofInstant(CartServiceTest.NOW.minusSeconds(1), ZoneOffset.UTC), cart.sessionNo());
                state.outage().set(true); // Historical reads cannot depend on the current catalog.
                assertThatThrownBy(() -> state.service().customerGetCart(cart.cartNo(), buyer))
                        .isInstanceOf(BizException.class);
                state.f().sql().update("INSERT INTO fnb_order_batch VALUES('MAIN','E','A',?,'B','OTHER','BOUND',0)",
                        cart.cartNo());
                state.f().sql().update("INSERT INTO fnb_core_order_binding VALUES('MAIN','E','B','BOUND',0)");
                assertThatThrownBy(() -> state.service().customerGetCart(cart.cartNo(), buyer))
                        .isInstanceOf(BizException.class);
                state.f().sql().update("UPDATE fnb_order_batch SET buyer_no='BUYER',status='UNKNOWN'");
                assertThatThrownBy(() -> state.service().customerGetCart(cart.cartNo(), buyer))
                        .isInstanceOf(BizException.class);
                state.f().sql().update("UPDATE fnb_order_batch SET status='BOUND'");
                var scope = DataScopeSpec.of(ScopeDim.MERCHANT, Set.of("E"));
                DataScopeContext.set(scope);
                try {
                    var historical = state.service().customerGetCart(cart.cartNo(), buyer).cart();
                    assertThat(historical.status()).isEqualTo("CLOSED");
                    assertThat(historical.buyerNo()).isNull();
                    assertThat(DataScopeContext.current()).isSameAs(scope);
                    assertThat(DataScopeContext.isSkipped()).isFalse();
                } finally { DataScopeContext.clear(); }
                for (CartAccess denied : java.util.List.of(new CartAccess(null, null, "OTHER"),
                        new CartAccess(null, null, null), new CartAccess(cart.sessionNo(), "expired", "BUYER"))) {
                    assertThatThrownBy(() -> state.service().customerGetCart(cart.cartNo(), denied))
                            .isInstanceOf(BizException.class);
                }
                assertThatThrownBy(() -> state.service().customerArchiveLine(cart.cartNo(), "LINE", buyer,
                        new CartVersionReq(cart.version()), "no-write-bypass")).isInstanceOf(BizException.class);
                state.f().sql().update("UPDATE fnb_order_batch SET store_no='OTHER'");
                assertThatThrownBy(() -> state.service().customerGetCart(cart.cartNo(), buyer))
                        .isInstanceOf(BizException.class);
                state.f().sql().update("UPDATE fnb_order_batch SET store_no='A'");
                state.f().sql().update("UPDATE fnb_check_cart SET order_source='QR' WHERE cart_no=?", cart.cartNo());
                assertThatThrownBy(() -> state.service().customerGetCart(cart.cartNo(), buyer))
                        .isInstanceOf(BizException.class);
                state.f().sql().update("UPDATE fnb_check_cart SET order_source='H5',status='OPEN' WHERE cart_no=?",
                        cart.cartNo());
                assertThatThrownBy(() -> state.service().customerGetCart(cart.cartNo(), buyer))
                        .isInstanceOf(BizException.class);
            });
        } finally { fixture.cleanupFixture(); }
    }
    @Test void uncertainOriginalPlacementBlocksRealCartEditsWithoutClosingOrChangingSourceLines() {
        CartServiceTest fixture = new CartServiceTest();
        try {
            fixture.run(state -> {
                var buyer = new CartAccess(null, null, "BUYER");
                var cart = state.service().customerCreateCart("A", buyer,
                        new CreateCustomerCartReq(null, null, "PICKUP", null), "pending-source").cart();
                var original = new OrderLineInput("LINE", "MENU", "ITEM", "REV", "GOODS", "SKU",
                        BigDecimal.ONE, null, null, List.of(), List.of());
                var edited = state.service().customerReplaceLine(cart.cartNo(), "LINE", buyer,
                        new ReplaceCartLineReq(original, cart.version()), "first-line");
                state.f().sql().update("INSERT INTO fnb_order_batch VALUES('MAIN','E','A',?,'B','BUYER','UNKNOWN',0)",
                        cart.cartNo());
                var changed = new OrderLineInput("LINE", "MENU", "ITEM", "REV", "GOODS", "SKU",
                        new BigDecimal("2"), null, null, List.of(), List.of());
                assertThatThrownBy(() -> state.service().customerReplaceLine(cart.cartNo(), "LINE", buyer,
                        new ReplaceCartLineReq(changed, edited.version()), "blocked-line"))
                        .isInstanceOf(BizException.class);
                var preserved = state.service().customerGetCart(cart.cartNo(), buyer).cart();
                assertThat(preserved.status()).isEqualTo("OPEN");
                assertThat(preserved.version()).isEqualTo(edited.version());
                assertThat(preserved.lines().getFirst().qty()).isEqualByComparingTo("1");
            });
        } finally { fixture.cleanupFixture(); }
    }

}
