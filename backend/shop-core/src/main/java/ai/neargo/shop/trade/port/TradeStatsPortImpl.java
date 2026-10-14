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

/**
 * trade 侧的看板聚合实现（{@link TradeStatsPort}）。
 *
 * <p><b>子订单上的聚合走数据域</b>（2026-08-14，运营端数据域接入 批①）：
 * 唯一的调用方是运营看板（{@code DashboardServiceImpl}），
 * 而看板此前对所有运营都是全平台口径 —— 配了「只看城西片区」的人，
 * 打开首屏看到的仍是全平台 GMV。没配数据域的账号是 {@code ALL}（空 = 不限定），
 * 超管恒 {@code ALL}，所以存量账号零变化。
 *
 * <p>`ord_after_sale` 上的两处仍解除数据域：那张表没有注册进
 * {@code DataScopeRegistration}，解除与否结果相同（未注册表 = 放行），
 * 留着只是不去动无关的行。
 */
@Component
public class TradeStatsPortImpl implements TradeStatsPort {

    /*
     * 成交口径**不在这里定义** —— 它在 {@link OrdSubOrder#TRANSACTED} 上，
     * 与商家看板、跨店对比、顾客画像共用同一份。
     *
     * 这两个常量此前是本类私有的，而商家侧另写了一套（`!= WAIT_PAY`）——
     * 两份口径没有任何地方声明过它们是两份，于是平台排行与商家后台的 GMV
     * 长期对不上，差额是被取消的单。谁也没报错，只有商家会打电话来问。
     */

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
        // 不绕过：与看板上其它几个数（走 ord_sub_order，本就不绕）同一口径。
        // 详见 merchantTotals 里那段注释
        return afterSaleMapper.selectCount(Wrappers.<OrdAfterSale>lambdaQuery()
                .in(OrdAfterSale::getStatus, OPEN_AFTER_SALE));
    }

    @Override
    public double todayRedeemRate() {
        long from = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        List<OrdSubOrder> today = subOrderMapper.selectList(Wrappers.<OrdSubOrder>lambdaQuery()
                .in(OrdSubOrder::getFulfillment,
                        List.of(OrdSubOrder.STORE_PICKUP, OrdSubOrder.NEIGHBOR_PICKUP))
                .in(OrdSubOrder::getStatus,
                        List.of(OrdSubOrder.FULFILLING, OrdSubOrder.COMPLETED))
                .ge(OrdSubOrder::getCreatedAt,
                        java.time.LocalDateTime.ofInstant(
                                java.time.Instant.ofEpochMilli(from), ZoneId.systemDefault())));
        if (today.isEmpty()) {
            // 0 而不是 1：「没有单要核销」不等于「全核销完了」
            return 0d;
        }
        long done = today.stream().filter(s -> OrdSubOrder.COMPLETED.equals(s.getStatus())).count();
        return (double) done / today.size();
    }

    @Override
    public Reach reach() {
        List<OrdSubOrder> all = subOrderMapper.selectList(Wrappers.<OrdSubOrder>lambdaQuery());
        long ordered = all.stream().map(OrdSubOrder::getUserNo)
                .filter(java.util.Objects::nonNull).distinct().count();
        // 转化漏斗的「付过款的人」：退款过的人**也算付过** —— 他确实转化了，
        // 退款是之后的另一件事。用不含退款的集合会让转化率随退款慢慢变低，
        // 而那与获客做得好不好毫无关系
        long paidUsers = all.stream().filter(s -> OrdSubOrder.TRANSACTED.contains(s.getStatus()))
                .map(OrdSubOrder::getUserNo).filter(java.util.Objects::nonNull).distinct().count();
        return new Reach(ordered, paidUsers);
    }

    /**
     * 按门店聚合。**与 merchantTotals 同一条查询口径**（成交态 + 已退款、
     * GMV 只累成交态），只是分组键换成 {@code store_no}。
     *
     * <p>不带售后数：售后表上没有门店号（{@code ord_after_sale} 只到主体），
     * 硬凑一个「按商家的售后数摊到他每家店」比不给更糟 ——
     * 那个数看起来是门店的，实际是商家的。
     */
    @Override
    public List<StoreTotal> storeTotals(LocalDate from) {
        List<OrdSubOrder> rows = subOrderMapper.selectList(Wrappers.<OrdSubOrder>lambdaQuery()
                .in(OrdSubOrder::getStatus, OrdSubOrder.TRANSACTED)
                .ge(from != null, OrdSubOrder::getCreatedAt,
                        from == null ? null : from.atStartOfDay()));

        record Cell(String merchantNo, long[] v) {
        }
        Map<String, Cell> byStore = new LinkedHashMap<>();
        for (OrdSubOrder s : rows) {
            if (s.getStoreNo() == null) {
                // 没有门店号的单（历史数据 / 平台直营）不该被凑进任何一家店
                continue;
            }
            Cell cell = byStore.computeIfAbsent(s.getStoreNo(),
                    k -> new Cell(s.getEntityNo(), new long[]{0L, 0L, 0L}));
            if (OrdSubOrder.REFUNDED.equals(s.getStatus())) {
                cell.v()[2] += 1;
                continue;
            }
            cell.v()[0] += nz(s.getPayAmount());
            cell.v()[1] += 1;
        }

        return byStore.entrySet().stream()
                .map(e -> new StoreTotal(e.getKey(), e.getValue().merchantNo(),
                        e.getValue().v()[0], e.getValue().v()[1], e.getValue().v()[2]))
                .sorted(java.util.Comparator.comparingLong(StoreTotal::gmv).reversed())
                .toList();
    }

    @Override
    public List<MerchantTotal> merchantTotals(LocalDate from) {
        /*
         * 取的是「成交态 + 已退款」两类，而不是只取成交态。
         *
         * 只取成交态的话，**单子全退光的商家会从排行里整个消失** —— 而售后率这一列
         * 存在的理由就是揪出「卖得多也赔得多」的店。实测撞到过：一笔 ¥30 的单低于
         * 极速退阈值，自动退款后那家商家凭空不见了。
         *
         * GMV 仍只累成交态（实收），与 paidTotals 同口径 —— 看板上下两层不能有两个 GMV 定义。
         */
        List<OrdSubOrder> rows = subOrderMapper.selectList(Wrappers.<OrdSubOrder>lambdaQuery()
                .in(OrdSubOrder::getStatus, OrdSubOrder.TRANSACTED)
                .ge(from != null, OrdSubOrder::getCreatedAt,
                        from == null ? null : from.atStartOfDay()));

        Map<String, long[]> byMerchant = new LinkedHashMap<>();
        for (OrdSubOrder s : rows) {
            if (s.getEntityNo() == null) {
                continue;
            }
            long[] cell = byMerchant.computeIfAbsent(s.getEntityNo(), k -> new long[]{0L, 0L, 0L});
            if (OrdSubOrder.REFUNDED.equals(s.getStatus())) {
                cell[2] += 1;
                continue;
            }
            cell[0] += nz(s.getPayAmount());
            cell[1] += 1;
        }
        if (byMerchant.isEmpty()) {
            return List.of();
        }
        /*
         * 售后数一次查回来按商家分组，不在循环里逐个查 —— 那是 N+1，
         * 而这个接口是看板首屏的一部分。
         *
         * **不按 from 过滤售后**：一单可能这个月成交、下个月才售后，
         * 按成交窗口去截售后会让「卖得多赔得也多」的商家看起来很干净。
         */
        /*
         * **不绕过**（2026-08-31，ord_after_sale 登记数据域时一并接上）：
         * 同一块看板上的订单数走的是不绕过的查询、已经被裁，
         * 售后数如果绕过就成了「**自己域内的订单数**配上**全平台的售后数**」——
         * 两个数出自不同口径而拼在同一行，比整块都不裁更难发现：它看起来是个正常的数。
         *
         * 这里其实也不会放大范围：`byMerchant.keySet()` 本身就来自那条裁过的订单查询。
         * 去掉绕过是为了**让口径显式一致**，而不是依赖「上游恰好已经裁过」这个巧合。
         */
        Map<String, Long> afterSales = afterSaleMapper.selectList(
                        Wrappers.<OrdAfterSale>lambdaQuery()
                                .in(OrdAfterSale::getEntityNo, byMerchant.keySet()))
                .stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        OrdAfterSale::getEntityNo, java.util.stream.Collectors.counting()));

        return byMerchant.entrySet().stream()
                .map(e -> new MerchantTotal(e.getKey(), e.getValue()[0], e.getValue()[1],
                        e.getValue()[2], afterSales.getOrDefault(e.getKey(), 0L)))
                .sorted(java.util.Comparator.comparingLong(MerchantTotal::gmv).reversed())
                .toList();
    }

    private List<OrdSubOrder> paid(LocalDate from) {
        return subOrderMapper.selectList(Wrappers.<OrdSubOrder>lambdaQuery()
                .in(OrdSubOrder::getStatus, OrdSubOrder.TRANSACTED)
                .ge(from != null, OrdSubOrder::getCreatedAt,
                        from == null ? null : from.atStartOfDay()));
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }
}
