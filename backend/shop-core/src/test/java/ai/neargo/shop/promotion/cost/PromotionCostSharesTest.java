package ai.neargo.shop.promotion.cost;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PromotionCostSharesTest {
    @Test
    void allocationConservesAmountsAcrossRatiosAndEndpoints() {
        assertThat(PromotionCostShares.allocate(101, 2500, 7500))
                .isEqualTo(new PromotionCostShares.Shares(25, 76));
        assertThat(PromotionCostShares.allocate(0, 6500, 3500))
                .isEqualTo(new PromotionCostShares.Shares(0, 0));
        assertThat(PromotionCostShares.allocate(37, 10000, 0))
                .isEqualTo(new PromotionCostShares.Shares(37, 0));
        assertThat(PromotionCostShares.allocate(37, 0, 10000))
                .isEqualTo(new PromotionCostShares.Shares(0, 37));
        assertThat(PromotionCostShares.allocate(101, 2500, 7500).totalMinor()).isEqualTo(101);
    }

    @Test
    void allocationAvoidsLongMultiplicationOverflow() {
        assertThat(PromotionCostShares.allocate(Long.MAX_VALUE, 5000, 5000))
                .isEqualTo(new PromotionCostShares.Shares(4611686018427387903L, 4611686018427387904L));
        assertThat(PromotionCostShares.allocate(Long.MAX_VALUE, 10000, 0).totalMinor())
                .isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void cumulativeRefundsReverseExactlyAcrossDifferentInstallments() {
        var original = new PromotionCostShares.Shares(2, 5);
        var first = PromotionCostShares.reverse(original, 0, 2);
        var second = PromotionCostShares.reverse(original, 2, 4);
        var finalRefund = PromotionCostShares.reverse(original, 4, 7);

        assertThat(first).isEqualTo(new PromotionCostShares.Shares(0, -2));
        assertThat(second).isEqualTo(new PromotionCostShares.Shares(-1, -1));
        assertThat(finalRefund).isEqualTo(new PromotionCostShares.Shares(-1, -2));
        assertThat(first.headquartersMinor() + second.headquartersMinor() + finalRefund.headquartersMinor())
                .isEqualTo(-original.headquartersMinor());
        assertThat(first.storeMinor() + second.storeMinor() + finalRefund.storeMinor())
                .isEqualTo(-original.storeMinor());
    }

    @Test
    void oneMinorUnitRoundingTailIsReturnedOnFullRefund() {
        var original = PromotionCostShares.allocate(1, 5000, 5000);

        assertThat(original).isEqualTo(new PromotionCostShares.Shares(0, 1));
        assertThat(PromotionCostShares.reverse(original, 0, 1))
                .isEqualTo(new PromotionCostShares.Shares(0, -1));
    }

    @Test
    void zeroOriginalAmountAcceptsOnlyZeroCumulativeRefund() {
        var original = new PromotionCostShares.Shares(0, 0);

        assertThat(PromotionCostShares.reverse(original, 0, 0))
                .isEqualTo(new PromotionCostShares.Shares(0, 0));
        assertThatThrownBy(() -> PromotionCostShares.reverse(original, 0, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidAllocationInputsAreRejected() {
        assertThatThrownBy(() -> PromotionCostShares.allocate(-1, 5000, 5000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PromotionCostShares.allocate(1, -1, 10001))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PromotionCostShares.allocate(1, 4000, 5000))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidRefundInputsAreRejected() {
        assertThatThrownBy(() -> PromotionCostShares.reverse(new PromotionCostShares.Shares(-1, 2), 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PromotionCostShares.reverse(new PromotionCostShares.Shares(1, -1), 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PromotionCostShares.reverse(new PromotionCostShares.Shares(4, 6), 3, 2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PromotionCostShares.reverse(new PromotionCostShares.Shares(4, 6), 0, 11))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PromotionCostShares.reverse(new PromotionCostShares.Shares(1, 0), -1, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PromotionCostShares.Shares(Long.MAX_VALUE, 1).totalMinor())
                .isInstanceOf(ArithmeticException.class);
    }
}
