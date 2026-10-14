package ai.neargo.shop.trade.order.machine.payment;

/**
 * 支付单迁移要读的事实（由调用方加载好，状态机只读）。
 *
 * @param offlinePay     下单选的是当面付
 * @param authorized     执行者对这张单有权限（本人 / 本店 / 代客权限）—— 归属在加载时判
 * @param amountMatches  回调金额与应付一致
 * @param deadlinePassed 已过支付截止时刻
 */
public record PaymentContext(boolean offlinePay, boolean authorized, boolean amountMatches,
                             boolean deadlinePassed) {
}
