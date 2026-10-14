package ai.neargo.shop.trade.order.machine.order;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.trade.order.statemachine.Guard;
import ai.neargo.shop.trade.order.statemachine.StateMachine;

import java.util.Set;

import static ai.neargo.shop.trade.order.machine.order.OrderEvent.*;
import static ai.neargo.shop.trade.order.machine.order.OrderStatus.*;
import static ai.neargo.shop.trade.order.statemachine.Actor.*;

/**
 * 订单状态机（订单状态机-完整方案 §3）。
 *
 * <p>两条刻意的收窄：<b>部分退款不触发任何迁移</b>（只改退款进度）；
 * <b>人工干预只能做非资金迁移</b>（往前推到履约中 / 已完成），动钱的走售后裁决或对账处置。
 */
public final class OrderMachine {

    private static final Guard<OrderContext> AUTHORIZED = Guard.of(OrderContext::authorized, ErrorCode.FORBIDDEN);
    private static final Guard<OrderContext> NOT_PREPARED = Guard.of(c -> !c.prepared(), ErrorCode.CONFLICT);
    private static final Guard<OrderContext> NO_OPEN_AFTER_SALE =
            Guard.of(c -> !c.openAfterSale(), ErrorCode.CONFLICT);
    private static final Guard<OrderContext> ALL_SHIPPED =
            Guard.of(OrderContext::allLinesShipped, ErrorCode.ORDER_STATE_ILLEGAL);
    private static final Guard<OrderContext> AUTO_RECEIPT_DUE =
            Guard.of(OrderContext::autoReceiptDue, ErrorCode.ORDER_STATE_ILLEGAL);
    private static final Guard<OrderContext> REFUND_COMPLETE =
            Guard.of(OrderContext::fullyRefunded, ErrorCode.ORDER_STATE_ILLEGAL);
    private static final Guard<OrderContext> REMARK = Guard.of(OrderContext::remarkPresent, ErrorCode.BAD_REQUEST);

    public static final StateMachine<OrderStatus, OrderEvent, OrderContext> MACHINE =
            StateMachine.<OrderStatus, OrderEvent, OrderContext>builder("order")
                    // O1 付款（级联自支付单）：服务类付款即出码
                    .from(WAIT_PAY).on(PAY_CONFIRMED).by(SYSTEM)
                    .toOneOf(Set.of(WAIT_FULFILL, FULFILLING), (f, c) -> c.serviceClass() ? FULFILLING : WAIT_FULFILL)
                    .emit("OrderPaid").add()
                    // O2 取消 / 关单（级联自支付单）
                    .from(WAIT_PAY).on(CANCEL).by(SYSTEM).to(CANCELLED).add()
                    // O3 开始备货：一次、不改状态；有进行中售后时不许点
                    .from(WAIT_FULFILL).on(PREPARE).by(MERCHANT).when(AUTHORIZED, NOT_PREPARED, NO_OPEN_AFTER_SALE)
                    .stay().emit("OrderPrepared").add()
                    // O4 首张发货单揽收（级联自发货单）
                    .from(WAIT_FULFILL).on(SHIPPED).by(SYSTEM).to(FULFILLING).emit("OrderShipped").add()
                    // O5 自提到店
                    .from(WAIT_FULFILL).on(ARRIVED_AT_PICKUP).by(MERCHANT).when(AUTHORIZED).to(FULFILLING)
                    .emit("OrderArrived").add()
                    // O6 核销：降级单只推进到履约中，由买家确认收口
                    .from(WAIT_FULFILL, FULFILLING).on(VERIFIED).by(MERCHANT).when(AUTHORIZED)
                    .toOneOf(Set.of(FULFILLING, COMPLETED), (f, c) -> c.requireBuyerConfirm() ? FULFILLING : COMPLETED)
                    .emit("OrderVerified").add()
                    // O7 商家标记送达：自送单可能已在「配送中」（FULFILLING），也可能直接从待发货送达
                    .from(WAIT_FULFILL, FULFILLING).on(DELIVERED_BY_MERCHANT).by(MERCHANT).when(AUTHORIZED).to(COMPLETED)
                    .emit("OrderCompleted").add()
                    // O8 买家确认收货：也可从待履约出发 —— 微信购物订单的「确认收货」组件不等我们标发货
                    .from(WAIT_FULFILL, FULFILLING).on(RECEIPT_CONFIRMED).by(BUYER).when(AUTHORIZED, ALL_SHIPPED).to(COMPLETED)
                    .emit("OrderCompleted").add()
                    // O9 自动确认收货（不是退款，保留自动）；有进行中售后不收口
                    .from(FULFILLING).on(RECEIPT_AUTO_CONFIRMED).by(SYSTEM).when(AUTO_RECEIPT_DUE, NO_OPEN_AFTER_SALE)
                    .to(COMPLETED).emit("OrderCompleted").add()
                    // O10 全部行退完（级联自退款账本）
                    .from(WAIT_FULFILL, FULFILLING, COMPLETED).on(FULLY_REFUNDED).by(SYSTEM).when(REFUND_COMPLETE)
                    .to(REFUNDED).emit("OrderRefunded").add()
                    // O11 人工干预：只能往前推，且不碰钱
                    .from(WAIT_FULFILL, FULFILLING).on(INTERVENE).by(PLATFORM).when(AUTHORIZED, REMARK)
                    .toOneOf(Set.of(FULFILLING, COMPLETED), OrderMachine::interveneTarget)
                    .emit("OrderIntervened").add()
                    .build();

    public static final Set<OrderStatus> TERMINALS = Set.of(CANCELLED, REFUNDED);

    private OrderMachine() {
    }

    /** 干预去向：必须比当前更靠后，且只在 {履约中, 已完成} 里选 */
    private static OrderStatus interveneTarget(OrderStatus from, OrderContext c) {
        OrderStatus to = c.interveneTarget();
        boolean forward = to == COMPLETED || (to == FULFILLING && from == WAIT_FULFILL);
        if (!forward) {
            throw BizException.of(ErrorCode.ORDER_STATE_ILLEGAL);
        }
        return to;
    }
}
