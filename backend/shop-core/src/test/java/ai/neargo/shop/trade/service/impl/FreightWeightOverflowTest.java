package ai.neargo.shop.trade.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 快递计费重 = 规格标称克重 × 件数。此前是 {@code Integer * int}：
 * 1 000 000 000 克 × 3 件溢出成负数，再被运费模板的 {@code max(0, 重量)} 抹成 0 —— 越重越包邮。
 */
class FreightWeightOverflowTest {

    @Test
    @DisplayName("★ 克重 × 件数溢出 int：拒单，而不是绕成负数后按 0 克算运费")
    void overflowIsRejectedNotWrapped() {
        assertThatThrownBy(() -> OrderServiceImpl.weighedGram(1_000_000_000, 3))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
    }

    @Test
    @DisplayName("正常的货照算；没填克重的件不进计费重（按件数走首重）")
    void normalAndUnweighed() {
        assertThat(OrderServiceImpl.weighedGram(2_500, 4)).isEqualTo(10_000);
        assertThat(OrderServiceImpl.weighedGram(null, 4)).isZero();
        assertThat(OrderServiceImpl.weighedGram(0, 4)).isZero();
    }
}
