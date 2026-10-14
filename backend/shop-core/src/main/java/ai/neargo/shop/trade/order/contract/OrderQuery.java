package ai.neargo.shop.trade.order.contract;

/** 买家订单列表的查询条件。本阶段只有状态（契约状态），后续扩展交付形态/时间。 */
public record OrderQuery(String status) {
}
