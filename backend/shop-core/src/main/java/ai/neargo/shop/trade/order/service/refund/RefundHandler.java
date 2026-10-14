package ai.neargo.shop.trade.order.service.refund;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.order.transition.Operator;
import ai.neargo.shop.trade.order.transition.OrderTransitions;

/**
 * 行级退款门面（订单服务 2.0 · W6）：校验可退数量 → 委托资金执行 → 整单退完则订单置 REFUNDED。
 *
 * <p>支持"买三退一"（按行按数量）。资金执行（分账/原路退/积分/券/库存）经 {@link RefundExecutor}
 * 复用现有引擎，本门面只管行级计量（{@link RefundCalc}）与订单状态联动（{@link OrderTransitions}）。
 */
public class RefundHandler {

    private final RefundReadStore read;
    private final RefundExecutor executor;
    private final OrderTransitions transitions;

    public RefundHandler(RefundReadStore read, RefundExecutor executor, OrderTransitions transitions) {
        this.read = read;
        this.executor = executor;
        this.transitions = transitions;
    }

    public void refundLine(String subOrderNo, String itemId, int refundQty, Operator op) {
        OrdSubOrder order = read.findOrder(subOrderNo);
        if (order == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        LineRefundInfo line = read.lineInfo(subOrderNo, itemId);
        if (line == null || !RefundCalc.canRefund(line.orderedQty(), line.refundedQty(), refundQty)) {
            throw BizException.of(ErrorCode.AFTERSALE_AMOUNT_INVALID);   // 退款数量超出可退范围
        }
        long amount = RefundCalc.amount(line.unitPrice(), refundQty);
        executor.refund(subOrderNo, itemId, refundQty, amount);
        if (RefundCalc.wholeOrderRefunded(read.lineRefundsAfter(subOrderNo))) {
            transitions.applySub(order, OrdSubOrder.REFUNDED, op, "整单退款完成");
        }
    }
}
