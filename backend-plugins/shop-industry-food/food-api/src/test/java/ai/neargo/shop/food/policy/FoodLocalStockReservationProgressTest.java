package ai.neargo.shop.food.policy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FoodLocalStockReservationProgressTest {
    private static BigDecimal q(String value) { return new BigDecimal(value); }

    @Test void cumulativeIssueIsIdempotentAndReleasePreservesConsumedQuantity() {
        var balance = new FoodLocalStockBalance(q("10"), q("4"));
        var progress = new FoodLocalStockReservationProgress(q("4"), q("0"), q("0"));
        var first = progress.issueTo(balance, q("1.5"));
        assertThat(first.delta()).isEqualByComparingTo("1.5");
        assertThat(first.balance().onHand()).isEqualByComparingTo("8.5");
        assertThat(first.balance().reserved()).isEqualByComparingTo("2.5");
        var replay = first.progress().issueTo(first.balance(), q("1.5"));
        assertThat(replay.delta()).isEqualByComparingTo("0");
        assertThat(replay.balance()).isEqualTo(first.balance());
        var released = replay.progress().releaseTo(replay.balance(), q("2.5"));
        assertThat(released.balance().onHand()).isEqualByComparingTo("8.5");
        assertThat(released.balance().reserved()).isEqualByComparingTo("0");
        assertThat(released.progress().issued()).isEqualByComparingTo("1.5");
    }

    @Test void rejectsRewindingAndExceedingTheOriginalReservation() {
        var balance = new FoodLocalStockBalance(q("9"), q("2"));
        var progress = new FoodLocalStockReservationProgress(q("4"), q("1"), q("1"));
        assertThatThrownBy(() -> progress.issueTo(balance, q("0.5"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> progress.issueTo(balance, q("3.1"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> progress.releaseTo(balance, q("3.1"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> progress.releaseTo(balance, q("0.5"))).isInstanceOf(IllegalArgumentException.class);
    }
}
