package ai.neargo.shop.settle.service;

import ai.neargo.shop.settle.entity.StlFeeRule;
import java.util.List;

/**
 * 费率取数与运营维护（落地清单 P1-4）。
 *
 * <p>替代此前写死在 {@code application.yml} 里的两个 {@code @Value} ——
 * 改一次费率不再需要改配置文件加重启。
 */
public interface FeeRuleService {

    /**
     * 取某一格在某一时刻生效的费率（万分比）。
     *
     * @param atMillis 判定时刻。<b>显式传入而不是内部取 now</b>：
     *                 结算重算、对账回溯都要能问「那个时点是多少」，
     *                 内部取 now 就只能回答「现在是多少」，而那正是这张表要解决的问题。
     * @return 命中不到任何规则时返回 0。宁可少收也不能凭空多收——
     *         费率查不到就按最高档收，是会真的多扣商家钱的。
     */
    int rateOf(String businessMode, String trafficSource, long atMillis);

    /**
     * 一次性取出某时刻的<b>全部</b>生效费率，供批量场景避免逐单查库。
     *
     * <p>键为 {@code businessMode + '|' + trafficSource}。
     */
    java.util.Map<String, Integer> effectiveRates(long atMillis);

    /** 全部规则（含历史版本），按格与生效时间排。运营要能看见「什么时候调过、调成什么」。 */
    List<StlFeeRule> rules();

    /**
     * 新增一个费率版本。
     *
     * <p><b>只增不改</b>：调费率是插新行，旧行永久保留。
     * 允许 {@code effectiveFrom} 为未来时刻，那就是预约生效。
     */
    StlFeeRule addRule(String businessMode, String trafficSource, int rateBp,
                       long effectiveFrom, String remark, String operator);
}
