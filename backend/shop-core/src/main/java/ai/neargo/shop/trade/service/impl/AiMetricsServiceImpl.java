package ai.neargo.shop.trade.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.trade.entity.OrdAfterSale;
import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.mapper.TradeMappers.AfterSaleMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.OrderItemMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.SubOrderMapper;
import ai.neargo.shop.trade.service.AiMetricsService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 见 {@link AiMetricsService}。做法：先把区间扫成<b>日桶</b>（每天一组计数），再按粒度合并、按聚合方式取值 ——
 * 对比期用同一套日桶逻辑再扫一次，保证两期口径逐字相同。
 */
@Service
public class AiMetricsServiceImpl implements AiMetricsService {

    /** IN 列表分块：子单号可能成千上万，一条 SQL 塞不下 */
    private static final int CHUNK = 500;

    private final SubOrderMapper subOrderMapper;
    private final AfterSaleMapper afterSaleMapper;
    private final OrderItemMapper itemMapper;

    public AiMetricsServiceImpl(SubOrderMapper subOrderMapper, AfterSaleMapper afterSaleMapper,
                                OrderItemMapper itemMapper) {
        this.subOrderMapper = subOrderMapper;
        this.afterSaleMapper = afterSaleMapper;
        this.itemMapper = itemMapper;
    }

    /** 一天的原始计数。金额为分。 */
    private static final class Day {
        long txCount;       // TRANSACTED 单数
        long txAmount;      // TRANSACTED 实付
        long okCount;       // COMPLETED 单数
        long okAmount;      // COMPLETED 实付
        long failCount;     // CANCELLED 单数
        long failAmount;
        long refundCount;   // 退款成功笔数（按 refunded_at）
        long refundAmount;

        void add(Day o) {
            txCount += o.txCount;
            txAmount += o.txAmount;
            okCount += o.okCount;
            okAmount += o.okAmount;
            failCount += o.failCount;
            failAmount += o.failAmount;
            refundCount += o.refundCount;
            refundAmount += o.refundAmount;
        }

        boolean empty() {
            return txCount == 0 && failCount == 0 && refundCount == 0;
        }
    }

    @Override
    public Result query(String merchantNo, Collection<String> storeNos, LocalDate from, LocalDate to,
                        List<String> metrics, String granularity, String compare, String aggregation) {
        List<String> ms = metrics == null ? List.of() : metrics.stream()
                .map(m -> m == null ? "" : m.trim().toLowerCase(Locale.ROOT))
                .filter(SUPPORTED::contains).distinct().toList();
        if (ms.isEmpty()) {
            return new Result(List.of(), false);
        }
        Map<LocalDate, Day> days = days(merchantNo, storeNos, from, to);
        boolean hasData = days.values().stream().anyMatch(d -> !d.empty());

        String cmp = compare == null ? "NONE" : compare.toUpperCase(Locale.ROOT);
        Map<LocalDate, Day> prevDays = null;
        LocalDate prevFrom = null;
        if (!"NONE".equals(cmp)) {
            prevFrom = shift(from, from, to, cmp);
            prevDays = days(merchantNo, storeNos, prevFrom, shift(to, from, to, cmp));
        }

        String gran = granularity == null || granularity.isBlank() ? null : granularity.toUpperCase(Locale.ROOT);
        String agg = aggregation == null || aggregation.isBlank() ? "SUM" : aggregation.toUpperCase(Locale.ROOT);
        List<Row> rows = new ArrayList<>();
        for (Map.Entry<String, List<LocalDate>> p : periods(from, to, gran).entrySet()) {
            Map<String, Cell> values = new LinkedHashMap<>();
            for (String m : ms) {
                Double v = value(m, p.getValue(), days, agg);
                Double prev = null;
                if (prevDays != null) {
                    List<LocalDate> shifted = p.getValue().stream()
                            .map(d -> shift(d, from, to, cmp)).toList();
                    prev = value(m, shifted, prevDays, agg);
                }
                values.put(m, new Cell(v, prev, pct(m, v, prev)));
            }
            rows.add(new Row(p.getKey(), values));
        }
        return new Result(rows, hasData);
    }

    /** 对比期的日期映射：WOW = 同长度的上一段；MOM = 上月同日；YOY = 去年同日 */
    private static LocalDate shift(LocalDate d, LocalDate from, LocalDate to, String cmp) {
        return switch (cmp) {
            case "MOM" -> d.minusMonths(1);
            case "YOY" -> d.minusYears(1);
            default -> d.minusDays(java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1);
        };
    }

    /** 周期 → 包含的日期。整段 = 一个 null 周期 */
    private static Map<String, List<LocalDate>> periods(LocalDate from, LocalDate to, String gran) {
        Map<String, List<LocalDate>> out = new LinkedHashMap<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            String key = gran == null ? null
                    : "MONTH".equals(gran) ? d.toString().substring(0, 7) : d.toString();
            out.computeIfAbsent(key, k -> new ArrayList<>()).add(d);
        }
        return out;
    }

    private static Double value(String metric, List<LocalDate> dates, Map<LocalDate, Day> days, String agg) {
        Day total = new Day();
        for (LocalDate d : dates) {
            Day x = days.get(d);
            if (x != null) {
                total.add(x);
            }
        }
        if (RATIO.contains(metric)) {
            // 比率类按整段累计后再除 —— 日比率的平均不等于整段比率
            return ratio(metric, total);
        }
        if ("SUM".equals(agg) || dates.size() <= 1) {
            return (double) counter(metric, total);
        }
        List<Long> daily = dates.stream().map(d -> counter(metric, days.getOrDefault(d, new Day()))).toList();
        return switch (agg) {
            case "AVG" -> daily.stream().mapToLong(Long::longValue).average().orElse(0);
            case "MAX" -> (double) daily.stream().mapToLong(Long::longValue).max().orElse(0);
            case "MIN" -> (double) daily.stream().mapToLong(Long::longValue).min().orElse(0);
            default -> (double) counter(metric, total);
        };
    }

    private static long counter(String metric, Day d) {
        return switch (metric) {
            case "success_amount" -> d.txAmount;
            case "order_count" -> d.txCount;
            case "success_count" -> d.okCount;
            case "refund_amount" -> d.refundAmount;
            case "refund_count" -> d.refundCount;
            case "fail_amount" -> d.failAmount;
            case "fail_count" -> d.failCount;
            default -> 0L;
        };
    }

    private static Double ratio(String metric, Day d) {
        return switch (metric) {
            case "avg_ticket" -> d.okCount == 0 ? null : (double) d.okAmount / d.okCount;
            case "refund_rate" -> d.okCount == 0 ? null : d.refundCount * 100.0 / d.okCount;
            case "success_rate" -> d.okCount + d.failCount == 0 ? null
                    : d.okCount * 100.0 / (d.okCount + d.failCount);
            default -> null;
        };
    }

    private static Double pct(String metric, Double v, Double prev) {
        if (RATIO.contains(metric) || v == null || prev == null || prev == 0d) {
            return null;
        }
        return Math.round((v - prev) * 10000.0 / prev) / 100.0;
    }

    /** 扫区间 → 日桶。时区 = JVM 默认，与工作台 {@code MerchantOrderServiceImpl#stats} 同一条时间轴 */
    private Map<LocalDate, Day> days(String merchantNo, Collection<String> storeNos, LocalDate from, LocalDate to) {
        Map<LocalDate, Day> out = new HashMap<>();
        if (storeNos != null && storeNos.isEmpty()) {
            return out;
        }
        Set<String> statuses = new java.util.HashSet<>(OrdSubOrder.TRANSACTED);
        statuses.add(OrdSubOrder.CANCELLED);
        var w = Wrappers.<OrdSubOrder>lambdaQuery().eq(OrdSubOrder::getEntityNo, merchantNo)
                .in(OrdSubOrder::getStatus, statuses)
                .ge(OrdSubOrder::getCreatedAt, from.atStartOfDay())
                .lt(OrdSubOrder::getCreatedAt, to.plusDays(1).atStartOfDay());
        if (storeNos != null) {
            w.in(OrdSubOrder::getStoreNo, storeNos);
        }
        for (OrdSubOrder o : DataScopeContext.executeWithoutScope(() -> subOrderMapper.selectList(w))) {
            if (o.getCreatedAt() == null) {
                continue;
            }
            Day d = out.computeIfAbsent(o.getCreatedAt().toLocalDate(), k -> new Day());
            long amt = nz(o.getPayAmount());
            if (OrdSubOrder.CANCELLED.equals(o.getStatus())) {
                d.failCount += 1;
                d.failAmount += amt;
                continue;
            }
            d.txCount += 1;
            d.txAmount += amt;
            if (OrdSubOrder.COMPLETED.equals(o.getStatus())) {
                d.okCount += 1;
                d.okAmount += amt;
            }
        }
        refunds(merchantNo, storeNos, from, to, out);
        return out;
    }

    /** 退款：按退款成功时刻（refunded_at·毫秒）归日；门店经子单归属（售后单上没有门店号） */
    private void refunds(String merchantNo, Collection<String> storeNos, LocalDate from, LocalDate to,
                         Map<LocalDate, Day> out) {
        ZoneId zone = ZoneId.systemDefault();
        long start = from.atStartOfDay(zone).toInstant().toEpochMilli();
        long end = to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        List<OrdAfterSale> rows = DataScopeContext.executeWithoutScope(() -> afterSaleMapper.selectList(
                Wrappers.<OrdAfterSale>lambdaQuery().eq(OrdAfterSale::getEntityNo, merchantNo)
                        .eq(OrdAfterSale::getStatus, OrdAfterSale.REFUNDED)
                        .ge(OrdAfterSale::getRefundedAt, start)
                        .lt(OrdAfterSale::getRefundedAt, end)));
        if (rows.isEmpty()) {
            return;
        }
        Set<String> allowed = null;
        if (storeNos != null) {
            Map<String, String> storeOf = storeOf(merchantNo,
                    rows.stream().map(OrdAfterSale::getSubOrderNo).distinct().toList());
            allowed = rows.stream().map(OrdAfterSale::getSubOrderNo)
                    .filter(s -> storeNos.contains(storeOf.get(s))).collect(Collectors.toSet());
        }
        for (OrdAfterSale a : rows) {
            if (allowed != null && !allowed.contains(a.getSubOrderNo())) {
                continue;
            }
            LocalDate day = java.time.Instant.ofEpochMilli(a.getRefundedAt()).atZone(zone).toLocalDate();
            Day d = out.computeIfAbsent(day, k -> new Day());
            d.refundCount += 1;
            d.refundAmount += nz(a.getRefundMinor());
        }
    }

    private Map<String, String> storeOf(String merchantNo, List<String> subOrderNos) {
        Map<String, String> out = new HashMap<>();
        for (List<String> chunk : chunks(subOrderNos)) {
            DataScopeContext.executeWithoutScope(() -> subOrderMapper.selectList(
                    Wrappers.<OrdSubOrder>lambdaQuery().eq(OrdSubOrder::getEntityNo, merchantNo)
                            .in(OrdSubOrder::getSubOrderNo, chunk)))
                    .forEach(o -> out.put(o.getSubOrderNo(), o.getStoreNo()));
        }
        return out;
    }

    @Override
    public List<SalesRow> salesRanking(String merchantNo, Collection<String> storeNos, LocalDate from, LocalDate to,
                                       boolean ascending, int limit) {
        if (storeNos != null && storeNos.isEmpty()) {
            return List.of();
        }
        // 已支付且未退款（PAID 不含 REFUNDED/CANCELLED）：退掉的货不算卖出去
        var w = Wrappers.<OrdSubOrder>lambdaQuery().eq(OrdSubOrder::getEntityNo, merchantNo)
                .in(OrdSubOrder::getStatus, OrdSubOrder.PAID)
                .ge(OrdSubOrder::getCreatedAt, from.atStartOfDay())
                .lt(OrdSubOrder::getCreatedAt, to.plusDays(1).atStartOfDay());
        if (storeNos != null) {
            w.in(OrdSubOrder::getStoreNo, storeNos);
        }
        List<String> subNos = DataScopeContext.executeWithoutScope(() -> subOrderMapper.selectList(w))
                .stream().map(OrdSubOrder::getSubOrderNo).toList();
        Map<String, long[]> agg = new TreeMap<>();
        Map<String, String> titles = new HashMap<>();
        for (List<String> chunk : chunks(subNos)) {
            for (OrdItem it : DataScopeContext.executeWithoutScope(() -> itemMapper.selectList(
                    Wrappers.<OrdItem>lambdaQuery().in(OrdItem::getSubOrderNo, chunk)))) {
                if (it.getGoodsNo() == null) {
                    continue;
                }
                long[] c = agg.computeIfAbsent(it.getGoodsNo(), k -> new long[2]);
                c[0] += it.getQty() == null ? 0 : it.getQty();
                c[1] += nz(it.getAmount());
                titles.putIfAbsent(it.getGoodsNo(), it.getTitle());
            }
        }
        Comparator<SalesRow> byQty = Comparator.comparingLong(SalesRow::qty);
        if (!ascending) {
            byQty = byQty.reversed();
        }
        return agg.entrySet().stream()
                .map(e -> new SalesRow(e.getKey(), titles.get(e.getKey()), e.getValue()[0], e.getValue()[1]))
                .sorted(byQty.thenComparing(SalesRow::goodsNo))
                .limit(Math.max(1, limit)).toList();
    }

    private static <T> List<List<T>> chunks(List<T> all) {
        List<List<T>> out = new ArrayList<>();
        for (int i = 0; i < all.size(); i += CHUNK) {
            out.add(all.subList(i, Math.min(all.size(), i + CHUNK)));
        }
        return out;
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }
}
