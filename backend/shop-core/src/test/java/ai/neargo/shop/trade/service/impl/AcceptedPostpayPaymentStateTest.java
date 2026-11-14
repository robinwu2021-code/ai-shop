package ai.neargo.shop.trade.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 核心读已受理后付单时的状态判定（ADR-059）：柜台后付人工实收把单推到 PAID 之后，
 * 日结、取消校验等经 {@code getForStore} 读这张单不能再报数据冲突（本机 8091 联调时日结被 10409 卡住）。
 */
class AcceptedPostpayPaymentStateTest {
    @Test
    void unpaidOrReceiptedPostpayIsConsistentAnythingElseIsAConflict() {
        // 还没付
        assertThat(IndustryOrderServiceImpl.acceptedPostpayPaymentState("WAIT_PAY", null, false)).isTrue();
        // 柜台后付人工实收覆盖
        assertThat(IndustryOrderServiceImpl.acceptedPostpayPaymentState("PAID", 1L, true)).isTrue();
        // PAID 但没有实收来源 —— 不知道钱从哪来，仍是冲突
        assertThat(IndustryOrderServiceImpl.acceptedPostpayPaymentState("PAID", 1L, false)).isFalse();
        // 状态与付款时间自相矛盾
        assertThat(IndustryOrderServiceImpl.acceptedPostpayPaymentState("WAIT_PAY", 1L, false)).isFalse();
        assertThat(IndustryOrderServiceImpl.acceptedPostpayPaymentState("PAID", null, true)).isFalse();
        assertThat(IndustryOrderServiceImpl.acceptedPostpayPaymentState("CLOSED", null, true)).isFalse();
    }
}
