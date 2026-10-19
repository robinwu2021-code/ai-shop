package ai.neargo.shop.trade.order.transition;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.order.support.MemoryStatusWriter;
import ai.neargo.shop.trade.entity.OrdStatusLog;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 写入门单测。用内存 fake store，验证"先断言、后写、幂等、商家闸、流水口径"。
 */
class OrderTransitionsTest {

    private MemoryStatusWriter store;
    private OrderTransitions tx;

    @BeforeEach
    void setUp() {
        store = new MemoryStatusWriter();
        tx = new OrderTransitions(store.writer());
    }

    private OrdSubOrder sub(String no, String status) {
        OrdSubOrder s = new OrdSubOrder();
        s.setSubOrderNo(no);
        s.setStatus(status);
        store.track(s);
        return s;
    }

    private OrdOrder order(String status) {
        OrdOrder o = new OrdOrder();
        o.setStatus(status);
        store.track(o);
        return o;
    }

    @Test
    @DisplayName("合法子单迁移：改状态 + 写状态 + 记流水（operatorType/label 对）")
    void legalSubTransitWritesAndLogs() {
        OrdSubOrder s = sub("SUB1", OrdSubOrder.WAIT_FULFILL);
        tx.applySub(s, OrdSubOrder.FULFILLING, Operator.merchant("M1"), "商家发货");

        assertThat(s.getStatus()).isEqualTo(OrdSubOrder.FULFILLING);
        assertThat(store.subs).hasSize(1);
        assertThat(store.logs).hasSize(1);
        OrdStatusLog log = store.logs.get(0);
        assertThat(log.getSubOrderNo()).isEqualTo("SUB1");
        assertThat(log.getStatus()).isEqualTo(OrdSubOrder.FULFILLING);
        assertThat(log.getLabel()).isEqualTo("商家发货");
        assertThat(log.getOperatorType()).isEqualTo(OrdStatusLog.BY_MERCHANT);
        assertThat(log.getOperatorNo()).isEqualTo("M1");
    }

    @Test
    @DisplayName("非法子单迁移：抛 BizException，且不写任何东西")
    void illegalSubTransitThrowsAndWritesNothing() {
        OrdSubOrder s = sub("SUB1", OrdSubOrder.COMPLETED);
        assertThatThrownBy(() -> tx.applySub(s, OrdSubOrder.WAIT_FULFILL, Operator.system(), "x"))
                .isInstanceOf(BizException.class);
        assertThat(store.subs).isEmpty();
        assertThat(store.logs).isEmpty();
        assertThat(s.getStatus()).isEqualTo(OrdSubOrder.COMPLETED);   // 状态没被改
    }

    @Test
    @DisplayName("商家闸：不能从未付款直接发货（WAIT_PAY→FULFILLING 拒）")
    void merchantGateRejectsShipBeforePaid() {
        OrdSubOrder s = sub("SUB1", OrdSubOrder.WAIT_PAY);
        assertThatThrownBy(() -> tx.applyMerchantSub(s, OrdSubOrder.FULFILLING, Operator.merchant("M1"), "发货"))
                .isInstanceOf(BizException.class);
        assertThat(store.subs).isEmpty();
    }

    @Test
    @DisplayName("系统侧同一条边可走（WAIT_PAY→FULFILLING，支付回调用）")
    void systemCanFulfillFromWaitPay() {
        OrdSubOrder s = sub("SUB1", OrdSubOrder.WAIT_PAY);
        assertThatCode(() -> tx.applySub(s, OrdSubOrder.FULFILLING, Operator.system(), "服务类付款即出码"))
                .doesNotThrowAnyException();
        assertThat(s.getStatus()).isEqualTo(OrdSubOrder.FULFILLING);
    }

    @Test
    @DisplayName("幂等：from==to 不写状态、不记流水")
    void idempotentNoop() {
        OrdSubOrder s = sub("SUB1", OrdSubOrder.FULFILLING);
        tx.applySub(s, OrdSubOrder.FULFILLING, Operator.system(), "重复");
        assertThat(store.subs).isEmpty();
        assertThat(store.logs).isEmpty();
    }

    @Test
    @DisplayName("主单迁移：改状态、写状态、不写子单流水")
    void orderTransitWritesNoLog() {
        OrdOrder o = order(OrdOrder.WAIT_PAY);
        tx.applyOrder(o, OrdOrder.PAID, Operator.system(), "支付回调");
        assertThat(o.getStatus()).isEqualTo(OrdOrder.PAID);
        assertThat(store.orders).hasSize(1);
        assertThat(store.logs).hasSize(1);
        assertThat(store.logs.getFirst().getAggregateType()).isEqualTo("PAYMENT");
    }

    @Test
    @DisplayName("主单非法迁移：PAID 是终态，不能再出")
    void orderPaidIsTerminal() {
        OrdOrder o = order(OrdOrder.PAID);
        assertThatThrownBy(() -> tx.applyOrder(o, OrdOrder.CANCELLED, Operator.system(), "x"))
                .isInstanceOf(BizException.class);
        assertThat(store.orders).isEmpty();
    }
}
