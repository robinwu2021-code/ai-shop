package ai.neargo.shop.trade.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.spi.trade.SettleSourcePort;
import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.mapper.TradeMappers.OrderItemMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.OrderMapper;
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
    private final OrderMapper orderMapper;

    public SettleSourcePortImpl(SubOrderMapper subOrderMapper, OrderItemMapper itemMapper,
                                OrderMapper orderMapper) {
        this.subOrderMapper = subOrderMapper;
        this.itemMapper = itemMapper;
        this.orderMapper = orderMapper;
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
         * 支付通道与下单端在**主单**上，一次查出来给所有子单用。
         * 一次支付覆盖整张订单，跨商家合单时几家用的是同一个通道 ——
         * 逐子单回查主单是同一个值查 N 遍。
         *
         * 查不到主单时两个字段留空：账单照常生成。结算这一步宁可少一个报表维度，
         * 也不能因为读不到通道就不给商家出账。
         */
        OrdOrder order = DataScopeContext.executeWithoutScope(() ->
                orderMapper.selectOne(Wrappers.<OrdOrder>lambdaQuery()
                        .eq(OrdOrder::getOrderNo, orderNo).last("LIMIT 1")));
        String payChannel = order == null ? null : order.getPayChannel();
        String payScene = order == null ? null : order.getPayScene();

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
                        s.getPickupNo(), qtyBySub.getOrDefault(s.getSubOrderNo(), 0),
                        s.getStoreNo(), nz(s.getPointsDeductMinor()), nz(s.getPointsFeeMinor()),
                        payChannel, payScene))
                .toList();
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }
}
