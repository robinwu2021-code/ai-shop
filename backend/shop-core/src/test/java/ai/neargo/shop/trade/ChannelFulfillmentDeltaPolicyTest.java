package ai.neargo.shop.trade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.trade.policy.ChannelFulfillmentDeltaPolicy;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

class ChannelFulfillmentDeltaPolicyTest {
    BigDecimal n(String value) {
        return new BigDecimal(value);
    }

    @Test
    void secondPartialActionReturnsItsDeltaRatherThanTheFullCumulativeQuantity() {
        assertThat(ChannelFulfillmentDeltaPolicy.delta(n("0.5"), n("1.25"), n("1.25"), "KG", "KG"))
                .isEqualByComparingTo("0.75");
    }

    @Test
    void originalKnownNoOpHasNoPhysicalDelta() {
        assertThat(ChannelFulfillmentDeltaPolicy.delta(n("0.5"), n("0.5"), n("1.25"), "KG", "KG"))
                .isZero();
    }

    @Test
    void unknownHistoryCannotBeSubstitutedWithZero() {
        assertThatThrownBy(
                        () ->
                                ChannelFulfillmentDeltaPolicy.delta(
                                        null, n("1.25"), n("1.25"), "KG", "KG"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void unitChangeOverOriginalAndReverseProgressAreRejected() {
        assertThatThrownBy(
                        () ->
                                ChannelFulfillmentDeltaPolicy.delta(
                                        n("1"), n("0.5"), n("1.25"), "KG", "KG"))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(
                        () ->
                                ChannelFulfillmentDeltaPolicy.delta(
                                        n("0"), n("2"), n("1.25"), "KG", "KG"))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(
                        () ->
                                ChannelFulfillmentDeltaPolicy.delta(
                                        n("0"), n("1"), n("1.25"), "EA", "KG"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void originalNumericPrecisionDoesNotRejectInsignificantTrailingZeros() {
        assertThat(
                        ChannelFulfillmentDeltaPolicy.delta(
                                n("0.5000000"), n("1.2500000"), n("1.2500000"), "KG", "KG"))
                .isEqualByComparingTo("0.75");
    }
}
