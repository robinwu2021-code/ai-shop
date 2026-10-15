package ai.neargo.shop.pay.settle.refund;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FinancialClosurePolicyTest {
    private final FinancialClosurePolicy policy = new FinancialClosurePolicy();

    @Test
    void retainedFreightRemainsPayableAfterGoodsRefund() {
        var facts = policy.evaluate(800, 0L, 0, 800, false, false);
        assertThat(facts.unpaidPayableMinor()).isEqualTo(800);
        assertThat(facts.legitimateRetainedMinor()).isEqualTo(800);
        assertThat(facts.fullyReversed()).isFalse();
        assertThat(facts.settlementReady()).isTrue();
    }

    @Test
    void priorPartialRefundThenPaymentAndRecoveryUsesActualPositions() {
        var partial = policy.evaluate(90, 180L, 90, 0, false, false);
        assertThat(partial.outstandingRecoveryMinor()).isZero();
        assertThat(partial.fullyReversed()).isFalse();
        var complete = policy.evaluate(0, 180L, 180, 0, false, false);
        assertThat(complete.fullyReversed()).isTrue();
    }

    @Test
    void paidButNotRecoveredIsNotClosed() {
        assertThat(policy.evaluate(0, 270L, 190, 0, false, false).outstandingRecoveryMinor()).isEqualTo(80);
    }

    @Test
    void pendingOrUnknownPaymentCannotClose() {
        assertThat(policy.evaluate(0, 0L, 0, 0, true, false).fullyReversed()).isFalse();
        assertThat(policy.evaluate(0, null, 0, 0, false, false).needsReview()).isTrue();
        assertThat(policy.evaluate(0, null, 0, 0, false, false).fullyReversed()).isFalse();
    }

    @Test
    void negativeLiabilityAndExcessRecoveryRequireReview() {
        assertThat(policy.evaluate(-1, 0L, 0, 0, false, false).needsReview()).isTrue();
        assertThat(policy.evaluate(0, 1L, 2, 0, false, false).needsReview()).isTrue();
    }
}
