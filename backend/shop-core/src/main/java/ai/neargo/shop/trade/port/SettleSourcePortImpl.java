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

    /**
     * 不变式 I1 的左边：这段时间里已支付的子单。
     *
     * <p><b>用 {@code TRANSACTED} 而不是 {@code PAID}</b>：退款单在支付那一刻
     * 也生成过结算单（之后才回退），所以「REFUNDED 但没有结算单」同样是违反。
     * 用 {@code PAID}（不含 REFUNDED）会把这一类<b>静默漏掉</b> ——
     * 而漏掉的恰好是钱已经动过两次的那些单。
     */
    @Override
    public List<PaidSubOrder> paidSubOrdersSince(long since, int limit) {
        List<OrdOrder> orders = DataScopeContext.executeWithoutScope(() ->
                orderMapper.selectList(Wrappers.<OrdOrder>lambdaQuery()
                        .ge(OrdOrder::getPaidAt, since)
                        .isNotNull(OrdOrder::getPaidAt)
                        .orderByAsc(OrdOrder::getPaidAt)
                        .last("limit " + Math.max(1, limit))));
        if (orders.isEmpty()) {
            return List.of();
        }
        Map<String, Long> paidAtOf = orders.stream()
                .collect(Collectors.toMap(OrdOrder::getOrderNo, OrdOrder::getPaidAt, (a, b) -> a));
        List<OrdSubOrder> subs = DataScopeContext.executeWithoutScope(() ->
                subOrderMapper.selectList(Wrappers.<OrdSubOrder>lambdaQuery()
                        .in(OrdSubOrder::getOrderNo, paidAtOf.keySet())
                        .in(OrdSubOrder::getStatus, OrdSubOrder.TRANSACTED)));
        return subs.stream()
                .map(x -> new PaidSubOrder(x.getSubOrderNo(), x.getOrderNo(),
                        paidAtOf.getOrDefault(x.getOrderNo(), 0L)))
                .toList();
    }

    /**
     * 不变式 I2 的右边：这批子单里**不是成交态**的那些。
     *
     * <p><b>查不到的也算异常</b>：子单号在 stl_bill 上而库里根本没有这个子单，
     * 比状态不对更严重 —— 它意味着账挂在一个不存在的单上。
     * 把「查不到」当成正常会让最严重的那一类静默消失。
     */
    @Override
    public List<String> notPaidAmong(java.util.Collection<String> subOrderNos) {
        if (subOrderNos == null || subOrderNos.isEmpty()) {
            return List.of();
        }
        List<OrdSubOrder> found = DataScopeContext.executeWithoutScope(() ->
                subOrderMapper.selectList(Wrappers.<OrdSubOrder>lambdaQuery()
                        .in(OrdSubOrder::getSubOrderNo, subOrderNos)));
        Map<String, String> statusOf = found.stream()
                .collect(Collectors.toMap(OrdSubOrder::getSubOrderNo, OrdSubOrder::getStatus,
                        (a, b) -> a));
        return subOrderNos.stream()
                /*
                 * **先判 null 再 contains。** TRANSACTED 是 Set.of(...)，
                 * 不可变集合对 null 键直接抛 NPE —— 而 null 恰好是这里最该报出来的那种
                 * （子单号在 stl_bill 上，库里却根本没有这个单）。
                 * 写成 `!TRANSACTED.contains(statusOf.get(no))` 的话，
                 * 整个巡检会在遇到第一条孤儿账时炸掉，而不是把它报出来。
                 */
                .filter(no -> {
                    String status = statusOf.get(no);
                    return status == null || !OrdSubOrder.TRANSACTED.contains(status);
                })
                .toList();
    }

    /** 不变式 I3 的左边：这段时间里标着「已发过积分」的子单 */
    @Override
    public List<String> pointsGrantedSince(long since, int limit) {
        return paidSubOrdersSince(since, limit).stream()
                .map(PaidSubOrder::subOrderNo)
                .filter(no -> {
                    OrdSubOrder sub = DataScopeContext.executeWithoutScope(() ->
                            subOrderMapper.selectOne(Wrappers.<OrdSubOrder>lambdaQuery()
                                    .eq(OrdSubOrder::getSubOrderNo, no).last("limit 1")));
                    return sub != null && Boolean.TRUE.equals(sub.getPointsGranted());
                })
                .toList();
    }

    /**
     * 把标记改回未发，让下一轮重发。
     *
     * <p><b>用 update 而不是先查后改</b>：这条修复动作可能与正常的发分链路
     * 并发跑到同一行上，先查后改会把中间那次写覆盖掉。
     */
    @Override
    public int clearPointsGranted(java.util.Collection<String> subOrderNos) {
        if (subOrderNos == null || subOrderNos.isEmpty()) {
            return 0;
        }
        return DataScopeContext.executeWithoutScope(() -> {
            OrdSubOrder patch = new OrdSubOrder();
            patch.setPointsGranted(false);
            return subOrderMapper.update(patch, Wrappers.<OrdSubOrder>lambdaUpdate()
                    .in(OrdSubOrder::getSubOrderNo, subOrderNos)
                    .eq(OrdSubOrder::getPointsGranted, true));
        });
    }

    /**
     * 不变式 I6 的判据：这批子单里已经不可能再成交的。
     *
     * <p>与 {@link #notPaidAmong} 刻意分开：那一个问的是「是不是成交态」
     * （WAIT_PAY 也算不是），这一个问的是「还有没有希望」——
     * WAIT_PAY 有希望，所以不在里面。两个判据看着相似，
     * 合成一个的话，等支付的单会被当成死单，用户在收银台前眼看着抵扣消失。
     */
    @Override
    public List<String> subOrdersNotAlive(java.util.Collection<String> subOrderNos) {
        if (subOrderNos == null || subOrderNos.isEmpty()) {
            return List.of();
        }
        List<OrdSubOrder> found = DataScopeContext.executeWithoutScope(() ->
                subOrderMapper.selectList(Wrappers.<OrdSubOrder>lambdaQuery()
                        .in(OrdSubOrder::getSubOrderNo, subOrderNos)));
        Map<String, String> statusOf = found.stream()
                .collect(Collectors.toMap(OrdSubOrder::getSubOrderNo, OrdSubOrder::getStatus,
                        (a, b) -> a));
        return subOrderNos.stream()
                .filter(no -> {
                    String status = statusOf.get(no);
                    // 查不到 = 下单事务回滚了而积分已经扣走 —— 这一类最该还
                    return status == null || OrdSubOrder.CANCELLED.equals(status);
                })
                .toList();
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
