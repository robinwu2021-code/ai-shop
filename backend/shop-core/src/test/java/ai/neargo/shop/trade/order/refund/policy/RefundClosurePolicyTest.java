package ai.neargo.shop.trade.order.refund.policy;

import ai.neargo.shop.trade.order.machine.order.OrderStatus;
import ai.neargo.shop.trade.order.refund.model.RefundClosureFacts;
import ai.neargo.shop.trade.order.refund.model.RefundProgress;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class RefundClosurePolicyTest {
    private final RefundClosurePolicy policy = new RefundClosurePolicy();

    @Test void partialCashDoesNotEndRemainingGoods() {
        var result = policy.decide(new RefundClosureFacts(2, 0, 1, 100, 50, true));
        assertThat(result.targetOrderStatus()).isNull();
        assertThat(result.cashProgress()).isEqualTo(RefundProgress.PARTIAL);
        assertThat(result.goodsClosed()).isFalse();
    }
    @Test void compensationDoesNotTerminateDeliveredGoods() {
        var result = policy.decide(new RefundClosureFacts(2, 2, 0, 100, 20, true));
        assertThat(result.targetOrderStatus()).isEqualTo(OrderStatus.COMPLETED);
        assertThat(result.allGoodsTerminated()).isFalse();
    }
    @Test void cancelledAndDeliveredGoodsCloseAsCompleted() {
        var result = policy.decide(new RefundClosureFacts(2, 1, 1, 100, 50, true));
        assertThat(result.targetOrderStatus()).isEqualTo(OrderStatus.COMPLETED);
    }
    @Test void cashSuccessCannotCloseUnsettledBenefits() {
        var result = policy.decide(new RefundClosureFacts(2, 0, 2, 100, 100, false));
        assertThat(result.goodsClosed()).isTrue();
        assertThat(result.targetOrderStatus()).isNull();
    }
    @Test void zeroCashStillClosesTerminatedGoods() {
        var result = policy.decide(new RefundClosureFacts(2, 0, 2, 0, 0, true));
        assertThat(result.targetOrderStatus()).isEqualTo(OrderStatus.REFUNDED);
        assertThat(result.cashProgress()).isEqualTo(RefundProgress.NONE);
    }
    @Test void retainedFreightDoesNotPreventGoodsClosure() {
        assertThat(policy.decide(new RefundClosureFacts(2, 0, 2, 110, 100, true))
                .targetOrderStatus()).isEqualTo(OrderStatus.REFUNDED);
    }
    @Test void unknownOrOverlappingQuantitiesFailClosed() {
        assertThatThrownBy(() -> policy.decide(new RefundClosureFacts(0, 0, 0, 0, 0, true)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.decide(new RefundClosureFacts(2, 1, 2, 100, 100, true)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.decide(new RefundClosureFacts(2, 0, 2, 100, 101, true)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
