package ai.neargo.shop.trade.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.spi.trade.SettleSourcePort;
import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.mapper.TradeMappers.OrderItemMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.OrderMapper;
import ai.neargo.shop.trade.entity.OrdAfterSale;
import ai.neargo.shop.trade.entity.OrdStatusLog;
import ai.neargo.shop.trade.mapper.TradeMappers.AfterSaleMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.StatusLogMapper;
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
    private final StatusLogMapper statusLogMapper;
    private final AfterSaleMapper afterSaleMapper;

    public SettleSourcePortImpl(SubOrderMapper subOrderMapper, OrderItemMapper itemMapper,
                                OrderMapper orderMapper, StatusLogMapper statusLogMapper,
                                AfterSaleMapper afterSaleMapper) {
        this.subOrderMapper = subOrderMapper;
        this.itemMapper = itemMapper;
        this.orderMapper = orderMapper;
        this.statusLogMapper = statusLogMapper;
        this.afterSaleMapper = afterSaleMapper;
    }

    /**
     * 未闭环的售后状态。<b>列「进行中」而不是列「已结束」</b>：
     * 将来加一个新状态时，漏登记的后果是「它被当成已闭环」——
     * 那会让一单争议中的钱照常放出去。反过来漏登记只是多等一轮，
     * 而多等一轮是安全的。
     */
    private static final java.util.Set<String> AFTER_SALE_OPEN = java.util.Set.of(
            OrdAfterSale.APPLIED, OrdAfterSale.REFUNDING, OrdAfterSale.ARBITRATING);

    @Override
    public List<SettleReadiness> settleReadiness(java.util.Collection<String> subOrderNos) {
        if (subOrderNos == null || subOrderNos.isEmpty()) {
            return List.of();
        }
        /*
         * 完成时刻取**状态流水**里进 COMPLETED 那一刻，不取子单的 updated_at ——
         * 后者会被任何一次无关改动（补个备注、改个地址）推后，
         * 而 T2 一推后，整批的应结日跟着往后挪，商家的钱莫名其妙晚到。
         */
        Map<String, Long> completedAt = DataScopeContext.executeWithoutScope(() ->
                        statusLogMapper.selectList(Wrappers.<OrdStatusLog>lambdaQuery()
                                .in(OrdStatusLog::getSubOrderNo, subOrderNos)
                                .eq(OrdStatusLog::getStatus, OrdSubOrder.COMPLETED)))
                .stream()
                // 同一子单可能有多条（重复流转），取**最早**那次完成：售后期从第一次完成起算
                .collect(Collectors.toMap(OrdStatusLog::getSubOrderNo, OrdStatusLog::getAt,
                        (a, b) -> a == null ? b : b == null ? a : Math.min(a, b)));

        java.util.Set<String> openAfterSale = DataScopeContext.executeWithoutScope(() ->
                        afterSaleMapper.selectList(Wrappers.<OrdAfterSale>lambdaQuery()
                                .in(OrdAfterSale::getSubOrderNo, subOrderNos)
                                .in(OrdAfterSale::getStatus, AFTER_SALE_OPEN)))
                .stream()
                .map(OrdAfterSale::getSubOrderNo)
                .collect(Collectors.toSet());

        List<SettleReadiness> out = new java.util.ArrayList<>();
        for (String no : subOrderNos) {
            Long at = completedAt.get(no);
            if (at == null) {
                // 还没完成 —— **不返回**，让调用方看见「这单不在结果里」而不是收到一个 0
                continue;
            }
            out.add(new SettleReadiness(no, at, openAfterSale.contains(no)));
        }
        return out;
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
