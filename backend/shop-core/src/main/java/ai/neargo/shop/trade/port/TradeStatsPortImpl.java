package ai.neargo.shop.trade.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.spi.trade.TradeStatsPort;
import ai.neargo.shop.trade.entity.OrdAfterSale;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.mapper.TradeMappers.AfterSaleMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.SubOrderMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** trade 侧的看板聚合实现（{@link TradeStatsPort}）。 */
@Component
public class TradeStatsPortImpl implements TradeStatsPort {

    /** 计入成交的状态。WAIT_PAY 不算 —— 没付钱的单不是成交 */
    private static final Set<String> PAID_STATES =
            Set.of(OrdSubOrder.WAIT_FULFILL, OrdSubOrder.FULFILLING, OrdSubOrder.COMPLETED);

    private static final Set<String> OPEN_AFTER_SALE =
            Set.of(OrdAfterSale.APPLIED, OrdAfterSale.ARBITRATING);

    private final SubOrderMapper subOrderMapper;
    private final AfterSaleMapper afterSaleMapper;

    public TradeStatsPortImpl(SubOrderMapper subOrderMapper, AfterSaleMapper afterSaleMapper) {
        this.subOrderMapper = subOrderMapper;
        this.afterSaleMapper = afterSaleMapper;
    }

    @Override
    public Totals paidTotals(LocalDate from) {
        List<OrdSubOrder> rows = paid(from);
        return new Totals(rows.stream().mapToLong(s -> nz(s.getPayAmount())).sum(), rows.size());
    }

    @Override
    public List<DailyTotal> dailyTotals(LocalDate from) {
        Map<String, long[]> byDay = new LinkedHashMap<>();
        for (OrdSubOrder s : paid(from)) {
            if (s.getCreatedAt() == null) {
                continue;
            }
            long[] cell = byDay.computeIfAbsent(s.getCreatedAt().toLocalDate().toString(),
                    k -> new long[]{0L, 0L});
            cell[0] += nz(s.getPayAmount());
            cell[1] += 1;
        }
        return byDay.entrySet().stream()
                .map(e -> new DailyTotal(e.getKey(), e.getValue()[0], e.getValue()[1]))
                .toList();
    }

    @Override
    public long openAfterSaleCount() {
        return DataScopeContext.executeWithoutScope(() ->
                afterSaleMapper.selectCount(Wrappers.<OrdAfterSale>lambdaQuery()
                        .in(OrdAfterSale::getStatus, OPEN_AFTER_SALE)));
    }

    @Override
    public double todayRedeemRate() {
        long from = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        List<OrdSubOrder> today = DataScopeContext.executeWithoutScope(() ->
                subOrderMapper.selectList(Wrappers.<OrdSubOrder>lambdaQuery()
                        .in(OrdSubOrder::getFulfillment,
                                List.of(OrdSubOrder.STORE_PICKUP, OrdSubOrder.NEIGHBOR_PICKUP))
                        .in(OrdSubOrder::getStatus,
                                List.of(OrdSubOrder.FULFILLING, OrdSubOrder.COMPLETED))
                        .ge(OrdSubOrder::getCreatedAt,
                                java.time.LocalDateTime.ofInstant(
                                        java.time.Instant.ofEpochMilli(from), ZoneId.systemDefault()))));
        if (today.isEmpty()) {
            // 0 而不是 1：「没有单要核销」不等于「全核销完了」
            return 0d;
        }
        long done = today.stream().filter(s -> OrdSubOrder.COMPLETED.equals(s.getStatus())).count();
        return (double) done / today.size();
    }

    @Override
    public Reach reach() {
        List<OrdSubOrder> all = DataScopeContext.executeWithoutScope(() ->
                subOrderMapper.selectList(Wrappers.<OrdSubOrder>lambdaQuery()));
        long ordered = all.stream().map(OrdSubOrder::getUserNo)
                .filter(java.util.Objects::nonNull).distinct().count();
        long paidUsers = all.stream().filter(s -> PAID_STATES.contains(s.getStatus()))
                .map(OrdSubOrder::getUserNo).filter(java.util.Objects::nonNull).distinct().count();
        return new Reach(ordered, paidUsers);
    }

    private List<OrdSubOrder> paid(LocalDate from) {
        return DataScopeContext.executeWithoutScope(() ->
                subOrderMapper.selectList(Wrappers.<OrdSubOrder>lambdaQuery()
                        .in(OrdSubOrder::getStatus, PAID_STATES)
                        .ge(from != null, OrdSubOrder::getCreatedAt,
                                from == null ? null : from.atStartOfDay())));
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }
}
