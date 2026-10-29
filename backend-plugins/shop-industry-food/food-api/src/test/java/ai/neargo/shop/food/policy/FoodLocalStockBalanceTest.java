package ai.neargo.shop.food.policy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FoodLocalStockBalanceTest {
    private static BigDecimal q(String value) { return new BigDecimal(value); }

    @Test void reservationIssueAndReleaseConserveStock() {
        var initial = new FoodLocalStockBalance(q("10.5"), q("1.25"));
        var reserved = initial.reserve(q("2.5"));
        assertThat(reserved.available()).isEqualByComparingTo("6.75");
        var issued = reserved.issueReserved(q("1.25"));
        assertThat(issued.onHand()).isEqualByComparingTo("9.25");
        assertThat(issued.reserved()).isEqualByComparingTo("2.5");
        var released = issued.release(q("0.5"));
        assertThat(released.onHand()).isEqualByComparingTo("9.25");
        assertThat(released.reserved()).isEqualByComparingTo("2");
        assertThat(released.available()).isEqualByComparingTo("7.25");
        assertThat(initial.onHand()).isEqualByComparingTo("10.5");
    }

    @Test void rejectsOversellOverissueAndAdjustmentBelowReservations() {
        var balance = new FoodLocalStockBalance(q("5"), q("3"));
        assertThatThrownBy(() -> balance.reserve(q("2.1"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> balance.release(q("3.1"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> balance.issueReserved(q("3.1"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> balance.adjust(q("-2.1"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FoodLocalStockBalance(q("2"), q("3"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void requiresPositiveExactQuantitiesAndRejectsExcessPrecision() {
        var balance = new FoodLocalStockBalance(q("1"), q("0"));
        assertThatThrownBy(() -> balance.reserve(q("0"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> balance.reserve(q("-1"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> balance.reserve(q("0.0000001"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> balance.adjust(q("0"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> balance.adjust(null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(balance.adjust(q("-0.25")).onHand()).isEqualByComparingTo("0.75");
    }
}
