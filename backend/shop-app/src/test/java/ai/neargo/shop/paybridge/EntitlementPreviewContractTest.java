package ai.neargo.shop.paybridge;

import ai.neargo.shop.spi.entitlement.EntitlementUsePort;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.CouponAllocation;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.QuoteCommand;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.QuoteView;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.PreviewQuery;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.PreviewView;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.PreviewReason;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.UseItem;
import ai.neargo.shop.spi.entitlement.EntitlementQueryPort.ActivationAction;
import ai.neargo.shop.spi.entitlement.EntitlementQueryPort.ActivationQuote;
import ai.neargo.shop.spi.entitlement.SourceDecisionPort;
import ai.neargo.shop.spi.entitlement.SourceRef;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Freeze readonly quote/preview semantics before implementing monetary and unit mutations. */
class EntitlementPreviewContractTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final SourceRef source = new SourceRef("CORE", "SUB_ORDER_USE", "so1", null, null, null, 2);
    private final Instant expires = Instant.parse("2026-11-01T00:00:00Z");
    private final String hash = "a".repeat(64);

    @Test
    void quoteRoundTripsOwnedSnapshotAndCopiesItsInputs() {
        var items = new ArrayList<>(List.of(new UseItem("line1", "g1", "护理", 2, 3000)));
        var selected = new ArrayList<>(List.of("coupon1"));
        var quote = new QuoteCommand(source, "u1", "m1", "s1", "CNY", 3000, 200, items, selected, hash);
        items.clear();
        selected.clear();
        assertThat(quote.itemsSnapshot()).hasSize(1);
        assertThat(quote.selectedInstanceNos()).containsExactly("coupon1");
        roundTrip(quote, QuoteCommand.class);
    }

    @Test
    void quoteRejectsMismatchedTotalsDuplicateLinesSelectionsAndOverflow() {
        var items = List.of(new UseItem("l1", "g1", "护理", 1, 3000));
        assertThatThrownBy(() -> new QuoteCommand(source, "u1", "m1", "s1", "CNY", 2999, 0,
                items, List.of("coupon1"), hash)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QuoteCommand(source, "u1", "m1", "s1", "CNY", 3000, 0,
                items, List.of("coupon1", "coupon1"), hash)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QuoteCommand(source, "u1", "m1", "s1", "CNY", 6000, 0,
                List.of(items.getFirst(), items.getFirst()), List.of("coupon1"), hash))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QuoteCommand(source, "u1", "m1", "s1", "CNY", Long.MAX_VALUE, 1,
                List.of(new UseItem("l1", "g1", "护理", 1, Long.MAX_VALUE)), List.of(), hash))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void quoteAllocationsMustExactlyExplainDiscount() {
        var allocation = new CouponAllocation("coupon1", "line1", 500);
        roundTrip(new QuoteView("q1", source, hash, 500, "CNY", List.of(allocation), expires), QuoteView.class);
        assertThatThrownBy(() -> new QuoteView("q1", source, hash, 501, "CNY", List.of(allocation), expires))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QuoteView("q1", source, hash, 1000, "CNY",
                List.of(allocation, allocation), expires)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void previewCarriesRevisionAndDoesNotRequireAnOrderBeforeWalkInCheckout() {
        roundTrip(new PreviewQuery("u1", "m1", "s1", "CNY", "card1", null, 1, null), PreviewQuery.class);
        var view = new PreviewView("preview1", "card1", "护理卡", "顾客*", true, PreviewReason.OK,
                1, 29, 3, hash, expires);
        roundTrip(view, PreviewView.class);
        assertThatThrownBy(() -> new PreviewView("preview1", "card1", "护理卡", "顾客*", true,
                PreviewReason.EXPIRED, 1, 29, 3, hash, expires)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void activationQuoteHasNoCommitOperationAndRejectsContradictoryAction() {
        roundTrip(new ActivationQuote("card1", "FIRST_USE", "ROLLING_DAYS", null, null,
                ActivationAction.ON_FIRST_COMMIT), ActivationQuote.class);
        assertThatThrownBy(() -> new ActivationQuote("card1", "PAID", "CALENDAR_MONTH",
                Instant.parse("2026-10-01T00:00:00Z"), expires, ActivationAction.ON_FIRST_COMMIT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unknownAuthorizationCannotCarryAnotherUsersDetails() {
        assertThatThrownBy(() -> new SourceDecisionPort.AuthorizationDecision("auth1",
                SourceDecisionPort.AuthorizationState.UNKNOWN, source, "u1", "m1", "s1", "CNY", 1L,
                hash, expires)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void usageHoldStatusCannotPretendSuccessWithoutItsIdentifiers() {
        assertThatThrownBy(() -> new EntitlementUsePort.HoldView("op1", EntitlementUsePort.State.HELD,
                null, null, Instant.now())).isInstanceOf(IllegalArgumentException.class);
    }

    private <T> void roundTrip(T value, Class<T> type) {
        assertThat(json.readValue(json.writeValueAsString(value), type)).isEqualTo(value);
    }
}
