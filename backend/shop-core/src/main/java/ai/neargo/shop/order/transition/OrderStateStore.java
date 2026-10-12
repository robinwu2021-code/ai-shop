package ai.neargo.shop.order.transition;

import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.entity.OrdStatusLog;
import ai.neargo.shop.trade.entity.OrdSubOrder;

/**
 * {@link OrderTransitions} 的持久化出口（小 port）。
 *
 * <p>把"写状态 + 写日志"从 MyBatis mapper 抽象出来，让写入门本身**不绑具体 ORM**、可纯逻辑单测
 * （测试用内存 fake 实现）。接线阶段再提供基于 {@code TradeMappers} 的实现
 * （届时负责 {@code executeWithoutScope} 数据域豁免等细节）。
 */
public interface OrderStateStore {

    /** 持久化主单状态（updateById）。 */
    void saveOrderStatus(OrdOrder order);

    /** 持久化子单状态（updateById）。 */
    void saveSubStatus(OrdSubOrder sub);

    /** 追加一条子单状态流水（insert ord_status_log）。 */
    void appendLog(OrdStatusLog log);
}
