package ai.neargo.shop.trade.event;

import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.shop.spi.trade.OrderEvents;
import ai.neargo.shop.trade.entity.OrdSubOrder;

/**
 * SUB_ORDER_COMPLETED 的唯一出口（异步与通知清单 N4）。
 *
 * <p>商家标记送达、自提核销、行业原行交付、行业门店自提核销、行业减量后履约满足五处收口都经这里，
 * 载荷一律取自刚迁移过的子单 —— 通知（userNo/fulfillment）、报表脏日（聚合 id）、
 * 库存出库（subOrderNo/orderNo）按字段名读它，某一处少填或填错不会报错，只会让某个消费方静默失效。
 *
 * <p>只在子单已到 {@link OrdSubOrder#COMPLETED} 时发：核销降级为「待买家确认」（FULFILLING）
 * 不是完成，由买家确认收货那条链收口。{@code TradeStatusWriter.order} 会把迁移后的状态写回子单，
 * 所以调用方在状态迁移之后、同一事务内调用即可；发布仍走 Outbox，与业务写同事务提交。
 */
public final class SubOrderCompletion {

    private SubOrderCompletion() {
    }

    public static boolean publishIfCompleted(OutboxEventBus bus, OrdSubOrder sub) {
        if (!OrdSubOrder.COMPLETED.equals(sub.getStatus())) {
            return false;
        }
        bus.publish(new OrderEvents.SubOrderCompleted(
                sub.getSubOrderNo(), sub.getOrderNo(), sub.getEntityNo(), sub.getUserNo(), sub.getFulfillment()));
        return true;
    }
}
