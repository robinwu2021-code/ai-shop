package ai.neargo.shop.trade.order.machine;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.trade.order.machine.aftersale.AfterSaleContext;
import ai.neargo.shop.trade.order.machine.aftersale.AfterSaleEvent;
import ai.neargo.shop.trade.order.machine.aftersale.AfterSaleMachine;
import ai.neargo.shop.trade.order.machine.aftersale.AfterSaleStatus;
import ai.neargo.shop.trade.order.machine.order.OrderContext;
import ai.neargo.shop.trade.order.machine.order.OrderEvent;
import ai.neargo.shop.trade.order.machine.order.OrderMachine;
import ai.neargo.shop.trade.order.machine.order.OrderStatus;
import ai.neargo.shop.trade.order.machine.payment.PaymentContext;
import ai.neargo.shop.trade.order.machine.payment.PaymentEvent;
import ai.neargo.shop.trade.order.machine.payment.PaymentMachine;
import ai.neargo.shop.trade.order.machine.payment.PaymentStatus;
import ai.neargo.shop.trade.order.statemachine.Actor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static ai.neargo.shop.trade.order.machine.aftersale.AfterSaleStatus.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 分流与关键守卫（表测试只比「有没有这条边」，这里比「运行时走哪条」）。 */
class MachineBehaviorTest {

    private static AfterSaleStatus approve(AfterSaleContext c) {
        return AfterSaleMachine.MACHINE.fire(APPLIED, AfterSaleEvent.APPROVE, Actor.MERCHANT, c).to();
    }

    @Test
    @DisplayName("★★ 商家同意分流：退货 → 等寄回；在途仅退款 → 等签收；其余 → 退钱；线下单 → 待线下退款")
    void approveRouting() {
        assertThat(approve(AfterSaleContext.builder().returnRefund(true).build())).isEqualTo(WAIT_RETURN);
        assertThat(approve(AfterSaleContext.builder().inTransit(true).build())).isEqualTo(WAIT_RECEIPT);
        assertThat(approve(AfterSaleContext.builder().build())).isEqualTo(REFUNDING);
        assertThat(approve(AfterSaleContext.builder().offlinePay(true).build())).isEqualTo(WAIT_OFFLINE_REFUND);
    }

    @Test
    @DisplayName("★★ 渠道不支持部分退 → 同意被拒，报 20013（给替代方案，不硬退）")
    void channelUnsupported() {
        assertThatThrownBy(() -> approve(AfterSaleContext.builder().channelSupports(false).build()))
                .extracting(e -> ((BizException) e).errorCode()).isEqualTo(ErrorCode.AFTERSALE_CHANNEL_PARTIAL_UNSUPPORTED);
    }

    @Test
    @DisplayName("★ 售后期外申请 → 20012")
    void windowClosed() {
        assertThatThrownBy(() -> AfterSaleMachine.MACHINE.fire(null, AfterSaleEvent.APPLY, Actor.BUYER,
                AfterSaleContext.builder().inWindow(false).build()))
                .extracting(e -> ((BizException) e).errorCode()).isEqualTo(ErrorCode.AFTERSALE_WINDOW_CLOSED);
    }

    @Test
    @DisplayName("★ 申请规则（生鲜发货后仅质量类）的错误码原样透出")
    void applyRule() {
        assertThatThrownBy(() -> AfterSaleMachine.MACHINE.fire(null, AfterSaleEvent.APPLY, Actor.BUYER,
                AfterSaleContext.builder().applyRuleError(ErrorCode.AFTERSALE_FRESH_QUALITY_ONLY).build()))
                .extracting(e -> ((BizException) e).errorCode()).isEqualTo(ErrorCode.AFTERSALE_FRESH_QUALITY_ONLY);
    }

    @Test
    @DisplayName("★★ 退款执行中买家不能撤销 —— 钱已在路上")
    void noWithdrawWhileRefunding() {
        assertThatThrownBy(() -> AfterSaleMachine.MACHINE.fire(REFUNDING, AfterSaleEvent.WITHDRAW, Actor.BUYER,
                AfterSaleContext.builder().build()))
                .extracting(e -> ((BizException) e).errorCode()).isEqualTo(ErrorCode.ORDER_STATE_ILLEGAL);
    }

    @Test
    @DisplayName("平台裁决退款：退货类货未回 → 等寄回；货已回 → 退钱")
    void arbitrateRouting() {
        var m = AfterSaleMachine.MACHINE;
        assertThat(m.fire(ARBITRATING, AfterSaleEvent.ARBITRATE_REFUND, Actor.PLATFORM,
                AfterSaleContext.builder().returnRefund(true).build()).to()).isEqualTo(WAIT_RETURN);
        assertThat(m.fire(ARBITRATING, AfterSaleEvent.ARBITRATE_REFUND, Actor.PLATFORM,
                AfterSaleContext.builder().returnRefund(true).goodsReturned(true).build()).to()).isEqualTo(REFUNDING);
    }

    @Test
    @DisplayName("★ 商家在 APPLIED 能做的事 = 同意 / 协商 / 驳回 / 申请介入（allowed 给前端画按钮）")
    void allowedForMerchant() {
        assertThat(AfterSaleMachine.MACHINE.allowed(APPLIED, Actor.MERCHANT, AfterSaleContext.builder().build()))
                .containsExactlyInAnyOrder(AfterSaleEvent.APPROVE, AfterSaleEvent.OFFER, AfterSaleEvent.REJECT,
                        AfterSaleEvent.ESCALATE);
    }

    @Test
    @DisplayName("订单：服务类付款即履约中；备货不改状态、已备过再点 → CONFLICT；有进行中售后不许备货")
    void orderBasics() {
        var m = OrderMachine.MACHINE;
        var svc = new OrderContext(true, true, false, false, false, true, false, false, null, false);
        assertThat(m.fire(OrderStatus.WAIT_PAY, OrderEvent.PAY_CONFIRMED, Actor.SYSTEM, svc).to())
                .isEqualTo(OrderStatus.FULFILLING);
        assertThat(m.fire(OrderStatus.WAIT_FULFILL, OrderEvent.PREPARE, Actor.MERCHANT, OrderContext.authorizedOnly())
                .changed()).isFalse();
        var prepared = new OrderContext(true, false, true, false, false, true, false, false, null, false);
        assertThatThrownBy(() -> m.fire(OrderStatus.WAIT_FULFILL, OrderEvent.PREPARE, Actor.MERCHANT, prepared))
                .extracting(e -> ((BizException) e).errorCode()).isEqualTo(ErrorCode.CONFLICT);
        var openAs = new OrderContext(true, false, false, true, false, true, false, false, null, false);
        assertThatThrownBy(() -> m.fire(OrderStatus.WAIT_FULFILL, OrderEvent.PREPARE, Actor.MERCHANT, openAs))
                .extracting(e -> ((BizException) e).errorCode()).isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    @DisplayName("★★ 人工干预只能往前推：履约中 → 履约中（原地）被拒；干预备注必填")
    void interveneForwardOnly() {
        var m = OrderMachine.MACHINE;
        var back = new OrderContext(true, false, false, false, false, true, false, false, OrderStatus.FULFILLING, true);
        assertThatThrownBy(() -> m.fire(OrderStatus.FULFILLING, OrderEvent.INTERVENE, Actor.PLATFORM, back))
                .extracting(e -> ((BizException) e).errorCode()).isEqualTo(ErrorCode.ORDER_STATE_ILLEGAL);
        var noRemark = new OrderContext(true, false, false, false, false, true, false, false, OrderStatus.COMPLETED, false);
        assertThatThrownBy(() -> m.fire(OrderStatus.WAIT_FULFILL, OrderEvent.INTERVENE, Actor.PLATFORM, noRemark))
                .extracting(e -> ((BizException) e).errorCode()).isEqualTo(ErrorCode.BAD_REQUEST);
    }

    @Test
    @DisplayName("支付单：线下下单落待当面收；回调金额不符被拒")
    void payment() {
        var m = PaymentMachine.MACHINE;
        assertThat(m.fire(null, PaymentEvent.PLACE, Actor.BUYER, new PaymentContext(true, true, true, false)).to())
                .isEqualTo(PaymentStatus.WAIT_OFFLINE_PAY);
        assertThatThrownBy(() -> m.fire(PaymentStatus.WAIT_PAY, PaymentEvent.PAY_SUCCEEDED, Actor.CHANNEL,
                new PaymentContext(false, true, false, false)))
                .extracting(e -> ((BizException) e).errorCode()).isEqualTo(ErrorCode.BAD_REQUEST);
    }
}
