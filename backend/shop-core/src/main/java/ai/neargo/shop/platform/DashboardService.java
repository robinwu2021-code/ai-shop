package ai.neargo.shop.platform;

import java.util.List;

/**
 * 运营工作台（P-16.1）。
 *
 * <p>此前三个接口全是 404，经营看板一直空着 —— ops-web 早就写好了契约、类型与页面，
 * 缺的是后端。与本轮反复撞到的「有能力没有消费方」相反：<b>有消费方没有产出</b>。
 *
 * <p><b>一条贯穿的原则：算不出来的数不编。</b>
 * 运营会照着这些数字去处置（催审、催核销、调预算），一个看起来像真的假数字
 * 比一个空白危险得多 —— 空白至少会让人去问。
 */
public interface DashboardService {

    KpiVO kpi();

    /**
     * 近 N 天的成交趋势。
     *
     * <p><b>无单的日子返回 0 而不是跳过</b>：跳过会让折线把两个不相邻的日子连起来，
     * 看上去像「一直在涨」，而中间其实是断的。
     */
    List<TrendPointVO> trend(int days);

    /**
     * 获客漏斗。
     *
     * <p><b>只返回有数据源的环节。</b> 设计上是「扫码 → 进店 → 注册 → 首单」四环，
     * 但前两环需要埋点，而平台没有任何扫码/进店的事件表 ——
     * 返回 0 会被读成「一个人都没扫码」，那是假的。少两行至少是真的。
     */
    List<FunnelRowVO> funnel();

    /**
     * 商家经营排行（P-16.1.2 / P-16.1.3）—— 大盘之下的第一层下钻。
     *
     * <p>大盘三个数回答「平台整体怎么样」，但运营下一句必然是「哪几家在拉高、
     * 哪几家在拖后腿」。没有这一层，那个问题只能靠翻订单列表人工数。
     *
     * @param days  回看天数，1–90（与 {@link #trend} 同一个夹取口径）
     * @param limit 取前几名，1–100
     */
    List<MerchantRankRowVO> merchantRanking(int days, int limit);

    /**
     * @param merchantName 商家名快照。**必须下发** —— 只给 merchantNo 的话运营看到的是
     *                     一列编号，要判断「这家是谁」还得再查一次
     * @param afterSaleRate 售后率 0–1 = 售后单数 ÷ 成交单数。无单时为 0（不是除零）。
     *                      与 GMV 并列才看得出「卖得多」是不是「赔得也多」
     */
    record MerchantRankRowVO(String merchantNo, String merchantName, long gmv, long orderCount,
                             long avgOrderValue, long afterSaleCount, double afterSaleRate) {
    }

    /**
     * @param gmv                   已支付订单的成交额（分）
     * @param orderCount            已支付订单数
     * @param avgOrderValue         客单价（分）。无单时为 0，<b>不是除零</b>
     * @param pendingMerchantAudit  待审入驻申请数
     * @param pendingAfterSale      待处理售后（含平台仲裁中）
     * @param redeemRate            今日核销率 0–1：今天已核销 ÷ 今天到过货的自提单。
     *                              分母为 0 时返回 0 —— 而不是 1（「没有单要核销」
     *                              不等于「全核销完了」，后者会让监控看板一片绿）
     */
    record KpiVO(long gmv, long orderCount, long avgOrderValue,
                 long pendingMerchantAudit, long pendingAfterSale, double redeemRate) {
    }

    /** @param date 日期 yyyy-MM-dd（按服务器时区切分，与 B 端「今日」同一口径） */
    record TrendPointVO(String date, long gmv, long orderCount) {
    }

    /** @param step REGISTER / FIRST_ORDER。SCAN 与 ENTER_STORE 没有埋点，不下发 */
    record FunnelRowVO(String step, long count) {
    }
}
