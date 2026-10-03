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

    /**
     * 按商家聚合的经营数据（P-16.1.2 商家排行 / P-16.1.3 商家经营）。
     *
     * <p><b>与 {@code MerchantOrderService.stats} 是同一份订单数据的两种切法</b>：
     * 那个答「这一家做了多少」（商家自己看），这个答「哪几家做得多」（平台横着比）。
     * 不另存排行榜表 —— 另存的迟早出现「榜上说 12 单、点进去只有 9 单」。
     *
     * @param from 起始日（含），为空不限
     * @return 按 GMV 降序，<b>只含有成交的商家</b>：零单商家排在末尾没有信息量，
     *         而把它们全带上会让「平台有多少家在做生意」这个数被稀释
     */
    List<MerchantTotal> merchantTotals(LocalDate from);

    /**
     * 按<b>门店</b>聚合的经营数据（门店③ 门店经营排行）。
     *
     * <p>与 {@link #merchantTotals} 是同一份订单数据的两种切法。
     * <b>为什么两个都要</b>：多门店商家的货、单、码都挂在门店上，
     * 而商家排行会把「一家很好、一家半死」平均成「还行」——
     * 那家半死的店在商家维度上永远看不见。
     *
     * <p>与 {@code /ops/stores/&#123;no&#125;/stats} 也不重复：那一个答
     * 「这家店最近怎么样」（要先知道看哪家），这个答「哪几家最该看」。
     *
     * @param from 起始日（含），为空不限
     * @return 按 GMV 降序，<b>只含有成交的门店</b>
     */
    List<StoreTotal> storeTotals(LocalDate from);

    /**
     * @param storeNo    门店号
     * @param merchantNo 它属于哪个商家 —— 排行上要能一眼看出「这两家差的店是同一个老板的」
     */
    record StoreTotal(String storeNo, String merchantNo, long gmv, long orderCount,
                      long refundedCount) {
    }

    /**
     * @param gmv            <b>实收</b>：只算仍在成交态的单，退掉的不计 —— 与 {@link #paidTotals}
     *                       同一口径，看板上下两层不能有两个 GMV 定义
     * @param orderCount     仍在成交态的单数
     * @param refundedCount  已退款的单数。<b>必须单列</b>：退掉的单会离开成交态，
     *                       只按成交态取商家集合的话，<b>一家单子全退光的商家会从排行里整个消失</b>——
     *                       而那恰恰是这张表最该显示的一家（实测撞到：¥30 的单低于极速退阈值，
     *                       自动退款后商家凭空不见了）
     * @param afterSaleCount 该商家的售后单数（含已完结）—— 与 GMV 并列才看得出
     *                       「卖得多」是不是「赔得也多」，那正是平台要盯的商家
     */
    record MerchantTotal(String merchantNo, long gmv, long orderCount, long refundedCount,
                         long afterSaleCount) {
    }

    record Totals(long gmv, long orderCount) {
    }

    record DailyTotal(String date, long gmv, long orderCount) {
    }

    record Reach(long ordered, long paid) {
    }
}
