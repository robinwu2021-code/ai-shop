package ai.neargo.shop.trade.order.machine.payment;

import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.trade.order.statemachine.Guard;
import ai.neargo.shop.trade.order.statemachine.StateMachine;

import java.util.Set;

import static ai.neargo.shop.trade.order.machine.payment.PaymentEvent.*;
import static ai.neargo.shop.trade.order.machine.payment.PaymentStatus.*;
import static ai.neargo.shop.trade.order.statemachine.Actor.*;

/**
 * 支付单状态机（订单状态机-完整方案 §2）。PAID 之后的一切变化都在订单上。
 */
public final class PaymentMachine {

    private static final Guard<PaymentContext> AUTHORIZED =
            Guard.of(PaymentContext::authorized, ErrorCode.FORBIDDEN);
    private static final Guard<PaymentContext> AMOUNT_MATCHES =
            Guard.of(PaymentContext::amountMatches, ErrorCode.BAD_REQUEST);
    private static final Guard<PaymentContext> DEADLINE_PASSED =
            Guard.of(PaymentContext::deadlinePassed, ErrorCode.ORDER_STATE_ILLEGAL);

    public static final StateMachine<PaymentStatus, PaymentEvent, PaymentContext> MACHINE =
            StateMachine.<PaymentStatus, PaymentEvent, PaymentContext>builder("payment")
                    // P1 下单：按支付方式落待付 / 待当面收
                    .create().on(PLACE).by(BUYER, PLATFORM).when(AUTHORIZED)
                    .toOneOf(Set.of(WAIT_PAY, WAIT_OFFLINE_PAY), (f, c) -> c.offlinePay() ? WAIT_OFFLINE_PAY : WAIT_PAY)
                    .emit("PaymentPlaced").add()
                    .create().on(POINTS_STAGE).by(BUYER, PLATFORM).when(AUTHORIZED)
                    .to(POINTS_PENDING).emit("PaymentPlacementPending").add()
                    .from(POINTS_PENDING).on(POINTS_READY).by(SYSTEM)
                    .toOneOf(Set.of(WAIT_PAY, WAIT_OFFLINE_PAY), (f, c) -> c.offlinePay() ? WAIT_OFFLINE_PAY : WAIT_PAY)
                    .emit("PaymentPlaced").add()
                    // P2 通道回调付款成功
                    .from(WAIT_PAY).on(PAY_SUCCEEDED).by(CHANNEL).when(AMOUNT_MATCHES).to(PAID)
                    .emit("PaymentPaid").add()
                    // P3 商家确认当面收款
                    .from(WAIT_OFFLINE_PAY).on(OFFLINE_CONFIRMED).by(MERCHANT).when(AUTHORIZED).to(PAID)
                    .emit("PaymentPaid").add()
                    // P4 买家 / 平台取消
                    .from(POINTS_PENDING, WAIT_PAY, WAIT_OFFLINE_PAY).on(CANCEL).by(BUYER, PLATFORM).when(AUTHORIZED).to(CANCELLED)
                    .emit("PaymentCancelled").add()
                    // P5 超时关闭（与 P2 并发时由 CAS 决出一方）
                    .from(POINTS_PENDING, WAIT_PAY).on(PAY_TIMEOUT).by(SYSTEM).when(DEADLINE_PASSED).to(CLOSED)
                    .emit("PaymentClosed").add()
                    // P6 关单后才到账：交易不成立，原路退回（售后 TRADE_VOID）
                    .from(CLOSED, CANCELLED).on(LATE_PAY_SUCCEEDED).by(CHANNEL).stay()
                    .emit("TradeVoidRefundRequired").add()
                    .build();

    /** 终态：之后不再有改变状态的迁移 */
    public static final Set<PaymentStatus> TERMINALS = Set.of(PAID, CANCELLED, CLOSED);

    private PaymentMachine() {
    }
}
