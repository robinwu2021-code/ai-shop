package ai.neargo.shop.trade.order.service.refund;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.entity.OrdStatusLog;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.order.transition.Operator;
import ai.neargo.shop.trade.order.transition.OrderStateStore;
import ai.neargo.shop.trade.order.transition.OrderTransitions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RefundHandlerTest {

    static class FakeExecutor implements RefundExecutor {
        int calls;
        long lastAmount;

        public void refund(String subOrderNo, String itemId, int refundQty, long amountMinor) {
            calls++;
            lastAmount = amountMinor;
        }
    }

    static class FakeStateStore implements OrderStateStore {
        public void saveOrderStatus(OrdOrder order) {
        }

        public void saveSubStatus(OrdSubOrder sub) {
        }

        public void appendLog(OrdStatusLog log) {
        }
    }

    static class FakeRead implements RefundReadStore {
        OrdSubOrder order;
        LineRefundInfo line;
        List<LineRefund> after;

        public OrdSubOrder findOrder(String subOrderNo) {
            return order;
        }

        public LineRefundInfo lineInfo(String subOrderNo, String itemId) {
            return line;
        }

        public List<LineRefund> lineRefundsAfter(String subOrderNo) {
            return after;
        }
    }

    private FakeRead read;
    private FakeExecutor executor;
    private RefundHandler handler;

    @BeforeEach
    void setUp() {
        read = new FakeRead();
        executor = new FakeExecutor();
        OrdSubOrder o = new OrdSubOrder();
        o.setSubOrderNo("ORD1");
        o.setStatus(OrdSubOrder.FULFILLING);
        read.order = o;
        handler = new RefundHandler(read, executor, new OrderTransitions(new FakeStateStore()));
    }

    @Test
    @DisplayName("买三退一：委托资金执行(金额=单价×件数)，订单未退完保持履约中")
    void partialLineRefund() {
        read.line = new LineRefundInfo(3, 0, 4980L);
        read.after = List.of(new LineRefund(3, 1));   // 退 1，还剩 2
        handler.refundLine("ORD1", "SK1", 1, Operator.merchant("M1"));
        assertThat(executor.calls).isEqualTo(1);
        assertThat(executor.lastAmount).isEqualTo(4980L);
        assertThat(read.order.getStatus()).isEqualTo(OrdSubOrder.FULFILLING);
    }

    @Test
    @DisplayName("退款使整单退完 → 订单 REFUNDED")
    void lastRefundCompletesOrder() {
        read.line = new LineRefundInfo(1, 0, 1000L);
        read.after = List.of(new LineRefund(1, 1));   // 唯一行退满
        handler.refundLine("ORD1", "SK1", 1, Operator.merchant("M1"));
        assertThat(read.order.getStatus()).isEqualTo(OrdSubOrder.REFUNDED);
    }

    @Test
    @DisplayName("退款数量超出可退 → 拒，不执行资金")
    void rejectsOverRefund() {
        read.line = new LineRefundInfo(3, 1, 4980L);   // 可退 2
        assertThatThrownBy(() -> handler.refundLine("ORD1", "SK1", 3, Operator.merchant("M1")))
                .isInstanceOf(BizException.class);
        assertThat(executor.calls).isZero();
    }

    @Test
    @DisplayName("订单不存在 → NOT_FOUND")
    void orderNotFound() {
        read.order = null;
        assertThatThrownBy(() -> handler.refundLine("ORD1", "SK1", 1, Operator.merchant("M1")))
                .isInstanceOf(BizException.class);
        assertThat(executor.calls).isZero();
    }
}
