package ai.neargo.shop.spi.trade;

import java.time.LocalDate;
import java.util.List;

/**
 * platform → trade：运营看板要的交易聚合。
 *
 * <p><b>为什么要这个 Port</b>：看板的数全是订单与售后的聚合，而 platform 域不能直连
 * trade 的 mapper —— ArchUnit 第 1 条当场就拦下来了（实测）。规则拦的正是这种
 * 「为了几个数捅穿一层边界」：一旦捅了，trade 域改一个列，运营看板跟着炸。
 *
 * <p>刻意只暴露<b>聚合结果</b>而不是订单实体：Port 一旦返回实体，
 * 调用方会顺手用上不该用的字段，边界就名存实亡。
 */
public interface TradeStatsPort {

    /**
     * 成交聚合。
     *
     * @param from 起始日（含）；为空表示不限
     * @return 无单时各项为 0，<b>不是 null</b> —— 看板上的 0 是「还没开张」，
     *         null 会渲染成 undefined
     */
    Totals paidTotals(LocalDate from);

    /**
     * 按日的成交额与单量。<b>只返回真的有单的日子</b> ——
     * 补齐空缺是展示层的事（那里知道要展示几天），域这一层不该替它决定跨度。
     */
    List<DailyTotal> dailyTotals(LocalDate from);

    /** 待处理售后数（商家未处理 + 已升平台仲裁）。平台看板要看得见后者 */
    long openAfterSaleCount();

    /**
     * 今日核销率 0–1：今天到过货的自提单里，已核销的占比。
     *
     * <p><b>分母为 0 时返回 0，不是 1。</b>「今天没有单要核销」与「全核销完了」
     * 是两件事，后者会让监控看板一片绿 —— 恰恰在最该被发现的那天。
     */
    double todayRedeemRate();

    /**
     * 下过单的人 / 其中付过钱的人。
     *
     * <p>获客漏斗只有这两环有数据源 —— 扫码与进店需要埋点，平台没有那两张事件表。
     */
    Reach reach();

    record Totals(long gmv, long orderCount) {
    }

    record DailyTotal(String date, long gmv, long orderCount) {
    }

    record Reach(long ordered, long paid) {
    }
}
