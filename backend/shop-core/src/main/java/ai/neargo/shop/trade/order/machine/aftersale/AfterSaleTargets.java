package ai.neargo.shop.trade.order.machine.aftersale;

import java.util.Set;

import static ai.neargo.shop.trade.order.machine.aftersale.AfterSaleStatus.*;

/**
 * 售后的多去向事件怎么分流（完整方案 §5.2 A4 / A6 / A16）。
 *
 * <p>「退钱这一步」只在一处定义（{@link #moneyStep}）：线上单 → 退款执行中；线下单 → 待商家线下退款。
 */
final class AfterSaleTargets {

    static final Set<AfterSaleStatus> MONEY = Set.of(REFUNDING, WAIT_OFFLINE_REFUND);
    static final Set<AfterSaleStatus> APPROVE_TARGETS = Set.of(REFUNDING, WAIT_OFFLINE_REFUND, WAIT_RECEIPT, WAIT_RETURN);
    static final Set<AfterSaleStatus> ACCEPT_TARGETS = Set.of(REFUNDING, WAIT_OFFLINE_REFUND, WAIT_RETURN);

    private AfterSaleTargets() {
    }

    static AfterSaleStatus moneyStep(AfterSaleStatus from, AfterSaleContext c) {
        return c.offlinePay ? WAIT_OFFLINE_REFUND : REFUNDING;
    }

    /** 商家同意：退货 → 等寄回；仅退款在途 → 等签收；其余 → 退钱 */
    static AfterSaleStatus approve(AfterSaleStatus from, AfterSaleContext c) {
        if (c.returnRefund) {
            return WAIT_RETURN;
        }
        return c.inTransit ? WAIT_RECEIPT : moneyStep(from, c);
    }

    /** 买家接受协商额 / 平台裁决退：退货类货还没回来 → 等寄回；否则退钱 */
    static AfterSaleStatus refundOrAwaitReturn(AfterSaleStatus from, AfterSaleContext c) {
        return c.returnRefund && !c.goodsReturned ? WAIT_RETURN : moneyStep(from, c);
    }
}
