package ai.neargo.shop.trade.order.service.impl;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.trade.order.assembler.OrderAssembler;
import ai.neargo.shop.trade.order.service.OrderQueryService;
import ai.neargo.shop.trade.order.service.OrderReadStore;
import ai.neargo.shop.trade.order.contract.OrderListItemVO;
import ai.neargo.shop.trade.order.contract.OrderQuery;
import ai.neargo.shop.trade.order.contract.UnifiedOrderVO;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.entity.OrdSubOrder;

import java.util.List;

/**
 * 只读查询实现：查库（经 {@link OrderReadStore}）→ 纯装配（{@link OrderAssembler}）。
 *
 * <p><b>本阶段不加 Spring 注解、不进组件扫描</b>：避免因缺 {@link OrderReadStore} bean 使现有上下文
 * 启动失败。接线阶段再加 {@code @Service} 并提供 ReadStore 实现。
 */
public class OrderQueryServiceImpl implements OrderQueryService {

    private final OrderReadStore store;

    public OrderQueryServiceImpl(OrderReadStore store) {
        this.store = store;
    }

    @Override
    public UnifiedOrderVO detail(String orderNo, String userNo) {
        OrdOrder order = store.findOrder(orderNo, userNo);
        if (order == null) {
            // 兼容：传进来的是子单号 → 回到它的主单
            OrdSubOrder sub = store.findSub(orderNo, userNo);
            if (sub == null) {
                return null;
            }
            order = store.findOrder(sub.getOrderNo(), userNo);
            if (order == null) {
                return null;
            }
        }
        String no = order.getOrderNo();
        return OrderAssembler.toUnified(order, store.subsOf(no), store.itemsOf(no));
    }

    @Override
    public PageData<OrderListItemVO> list(String userNo, OrderQuery query, long page, long size) {
        PageData<OrdOrder> p = store.pageOrders(userNo, query, page, size);
        List<OrderListItemVO> rows = p.records().stream()
                .map(o -> OrderAssembler.toListItem(o, store.subsOf(o.getOrderNo()), store.itemsOf(o.getOrderNo())))
                .toList();
        return PageData.of(rows, p.total(), p.page(), p.size());
    }
}
