package ai.neargo.shop.trade.order.transition;

import ai.neargo.shop.trade.order.statemachine.Actor;

/**
 * 交给转换门的一次「请求迁移」。
 *
 * @param no         聚合单号
 * @param anchorNo   时间线锚点：订单 / 售后 / 发货单写所属子单号；支付单写支付单号
 * @param operatorNo 执行人编号（商家账号 / 运营账号 / 买家用户号）；系统与通道为空
 * @param reason     写进流水的说明（干预备注、驳回理由、「商家超时提醒」等）
 * @param ctx        状态机守卫要读的事实（调用方加载）
 */
public record Command<E, C>(Aggregate aggregate, String no, String anchorNo, E event, Actor actor,
                            String operatorNo, String reason, C ctx) {
}
