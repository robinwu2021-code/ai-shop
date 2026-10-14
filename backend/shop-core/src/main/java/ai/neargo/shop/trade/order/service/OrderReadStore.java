package ai.neargo.shop.trade.order.service;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.trade.order.contract.OrderQuery;
import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.entity.OrdSubOrder;

import java.util.List;

/**
 * {@link OrderQueryService} 的只读查询出口（小 port）。把查库与装配分开，让查询服务可纯逻辑单测
 * （测试用内存 fake）。接线阶段提供基于 {@code TradeMappers} 的实现（负责属主过滤、分页、数据域）。
 *
 * <p>所有按属主的方法都带 {@code userNo}，由实现写进查询条件防 IDOR。
 */
public interface OrderReadStore {

    /** 按主单号 + 属主查主单；查不到返回 null。 */
    OrdOrder findOrder(String orderNo, String userNo);

    /** 按子单号 + 属主查子单（用于兼容旧的子单号入参）；查不到返回 null。 */
    OrdSubOrder findSub(String subOrderNo, String userNo);

    /** 该主单下全部子单（稳定序）。 */
    List<OrdSubOrder> subsOf(String orderNo);

    /** 该主单下全部行。 */
    List<OrdItem> itemsOf(String orderNo);

    /** 买家订单分页（主单粒度）。 */
    PageData<OrdOrder> pageOrders(String userNo, OrderQuery query, long page, long size);
}
