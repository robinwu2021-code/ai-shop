package ai.neargo.shop.trade.service;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 给 AI 经营助手（soukmind）的经营指标取数 —— {@code /internal/ai/v1/metrics/query} 与区间销量排行的实现。
 *
 * <p>为什么单开一个服务而不复用工作台的 {@code MerchantOrderService#stats}：工作台只有「今天 / 本月」两个固定窗，
 * 助手要的是<b>任意区间 × 任意指标</b>，还要按天/按月分桶与同比环比。但<b>口径与工作台同一份</b>：
 * 成交用 {@code OrdSubOrder.TRANSACTED}、金额用 {@code pay_amount}、按 {@code created_at} 在 JVM 时区切日 ——
 * 同一家店同一天，助手说的营业额必须等于工作台首页的数。
 *
 * <p>金额一律以<b>分</b>返回；换成元（以及带不带币种）是接口层的事，服务层不假设小数位。
 *
 * <p>不提供「净收入 / 净利润 / 结算额」：净成交属结算域，{@code OrdSubOrder.TRANSACTED} 的注释明确不许用减法凑；
 * 成本数据本系统没有。设计：{@code docs/technical/TDD-AI取数接口.md}。
 */
public interface AiMetricsService {

    /** 支持的指标码（soukmind 标准码）。不在其中的码被调用方忽略。 */
    List<String> SUPPORTED = List.of("success_amount", "order_count", "success_count", "avg_ticket",
            "refund_amount", "refund_count", "refund_rate", "fail_amount", "fail_count", "success_rate");

    /** 金额类指标（值为分）。 */
    List<String> MONEY = List.of("success_amount", "avg_ticket", "refund_amount", "fail_amount");

    /** 比率/均值类：按整段累计后再除，不参与日聚合，也不给变化率。 */
    List<String> RATIO = List.of("avg_ticket", "refund_rate", "success_rate");

    /**
     * 取数。
     *
     * @param storeNos    门店范围；{@code null} = 商户全部门店，空集合 = 没有可看的门店（全零）
     * @param from        区间首日（含）
     * @param to          区间末日（含）
     * @param granularity {@code null} 整段一行 / {@code DAY} / {@code MONTH}
     * @param compare     {@code null}/{@code NONE} / {@code WOW} / {@code MOM} / {@code YOY}
     * @param aggregation {@code SUM}（默认）/ {@code AVG} / {@code MAX} / {@code MIN}（按日）
     */
    Result query(String merchantNo, Collection<String> storeNos, LocalDate from, LocalDate to,
                 List<String> metrics, String granularity, String compare, String aggregation);

    /** 区间内已支付且未退款的子单明细，按商品聚合的销量排行。 */
    List<SalesRow> salesRanking(String merchantNo, Collection<String> storeNos, LocalDate from, LocalDate to,
                                boolean ascending, int limit);

    /**
     * @param series  每行一个周期：{@code period}（整段为 null）+ 每个指标的 {@link Cell}
     * @param hasData 区间内有没有成交 —— 没有时调用方要如实说「暂无数据」，不能当 0
     */
    record Result(List<Row> series, boolean hasData) {
    }

    record Row(String period, Map<String, Cell> values) {
    }

    /** 指标值：本期、对比期、变化率%（无对比 / 对比期为 0 / 比率类为 null）。金额类为分，比率类为百分数。 */
    record Cell(Double value, Double prev, Double pct) {
    }

    record SalesRow(String goodsNo, String title, long qty, long amountMinor) {
    }
}
