package ai.neargo.shop.trade.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.spi.trade.SettleSourcePort;
import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.mapper.TradeMappers.OrderItemMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.SubOrderMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class SettleSourcePortImpl implements SettleSourcePort {

    private final SubOrderMapper subOrderMapper;
    private final OrderItemMapper itemMapper;

    public SettleSourcePortImpl(SubOrderMapper subOrderMapper, OrderItemMapper itemMapper) {
        this.subOrderMapper = subOrderMapper;
        this.itemMapper = itemMapper;
    }

    @Override
    public List<SettleSource> settleSourcesOf(String orderNo) {
        List<OrdSubOrder> subs = DataScopeContext.executeWithoutScope(() ->
                subOrderMapper.selectList(Wrappers.<OrdSubOrder>lambdaQuery()
                        .eq(OrdSubOrder::getOrderNo, orderNo)));
        if (subs.isEmpty()) {
            return List.of();
        }
        /*
         * 件数一次查出来按子单归并，不逐单查 —— 一个订单拆几家就是几次往返，
         * 而结算是批量跑的，N+1 在这里会被放大成 N×M。
         * **含赠品**：赠品同样要分拣、要占货架，自提点的工作量不因为它不要钱就变少。
         */
        List<String> subNos = subs.stream().map(OrdSubOrder::getSubOrderNo).toList();
        Map<String, Integer> qtyBySub = DataScopeContext.executeWithoutScope(() ->
                        itemMapper.selectList(Wrappers.<OrdItem>lambdaQuery()
                                .in(OrdItem::getSubOrderNo, subNos))).stream()
                .collect(Collectors.groupingBy(OrdItem::getSubOrderNo,
                        Collectors.summingInt(i -> i.getQty() == null ? 0 : i.getQty())));

        return subs.stream()
                .map(s -> new SettleSource(s.getSubOrderNo(), s.getEntityNo(), s.getTrafficSource(),
                        nz(s.getPayAmount()), nz(s.getDiscountPlatform()), nz(s.getDiscountMerchant()),
                        s.getPickupNo(), qtyBySub.getOrDefault(s.getSubOrderNo(), 0)))
                .toList();
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }
}
