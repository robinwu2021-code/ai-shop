package ai.neargo.shop.trade.order.service.refund;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RefundCalcTest {

    @Test
    @DisplayName("可退数量 = 已下单 − 已退（不为负）")
    void refundableQty() {
        assertThat(RefundCalc.refundableQty(3, 1)).isEqualTo(2);
        assertThat(RefundCalc.refundableQty(3, 3)).isZero();
        assertThat(RefundCalc.refundableQty(3, 5)).isZero();
    }

    @Test
    @DisplayName("退款额 = 单价 × 件数")
    void amount() {
        assertThat(RefundCalc.amount(4980L, 2)).isEqualTo(9960L);
        assertThat(RefundCalc.amount(4980L, 0)).isZero();
    }

    @Test
    @DisplayName("退款件数合法性：正数且不超可退")
    void canRefund() {
        assertThat(RefundCalc.canRefund(3, 1, 2)).isTrue();
        assertThat(RefundCalc.canRefund(3, 1, 3)).isFalse();
        assertThat(RefundCalc.canRefund(3, 0, 0)).isFalse();
        assertThat(RefundCalc.canRefund(3, 3, 1)).isFalse();
    }

    @Test
    @DisplayName("整单退完：所有行退满才算（退券退分的判据）")
    void wholeOrderRefunded() {
        assertThat(RefundCalc.wholeOrderRefunded(List.of(
                new LineRefund(2, 2), new LineRefund(1, 1)))).isTrue();
        assertThat(RefundCalc.wholeOrderRefunded(List.of(
                new LineRefund(2, 2), new LineRefund(1, 0)))).isFalse();
        assertThat(RefundCalc.wholeOrderRefunded(List.of())).isFalse();
    }
}
