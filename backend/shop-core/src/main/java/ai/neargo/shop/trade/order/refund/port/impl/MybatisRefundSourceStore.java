package ai.neargo.shop.trade.order.refund.port.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.mapper.TradeMappers.OrderItemMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.OrderMapper;
import ai.neargo.shop.trade.order.refund.port.RefundSourceStore;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import java.util.List;

/** {@link RefundSourceStore} 的库实现。两条都走索引（sub_order_no / order_no），无 N+1。 */
public class MybatisRefundSourceStore implements RefundSourceStore {

    private final OrderItemMapper itemMapper;
    private final OrderMapper orderMapper;

    public MybatisRefundSourceStore(OrderItemMapper itemMapper, OrderMapper orderMapper) {
        this.itemMapper = itemMapper;
        this.orderMapper = orderMapper;
    }

    @Override
    public List<OrdItem> items(String subOrderNo) {
        return DataScopeContext.executeWithoutScope(() -> itemMapper.selectList(Wrappers.<OrdItem>lambdaQuery()
                .eq(OrdItem::getSubOrderNo, subOrderNo).orderByAsc(OrdItem::getId)));
    }

    @Override
    public String payChannel(String orderNo) {
        OrdOrder o = DataScopeContext.executeWithoutScope(() -> orderMapper.selectOne(Wrappers.<OrdOrder>lambdaQuery()
                .eq(OrdOrder::getOrderNo, orderNo).last("LIMIT 1")));
        return o == null ? null : o.getPayChannel();
    }
}
