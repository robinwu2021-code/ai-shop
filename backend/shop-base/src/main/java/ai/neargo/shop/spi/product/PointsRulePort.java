package ai.neargo.shop.spi.product;

import java.util.Optional;

/**
 * settle → product：这一行商品<b>配了</b>什么积分规则。
 *
 * <p><b>刻意只回答「配了什么」，不回答「该发多少分」。</b> 责任是这么分的：
 * <ul>
 *   <li><b>product 域</b>：哪一层的配置生效（商品例外 → 类目）。这是主数据的事</li>
 *   <li><b>settle 域</b>：没有任何配置时用平台兜底比例、以及怎么把规则换算成分。
 *       兜底比例是 {@code PointsConfig} 的一部分，属于结算口径</li>
 * </ul>
 * 合成一个「给我分数」的方法会把 settle 的兜底配置泄进 product 域。
 */
public interface PointsRulePort {

    /** 定额（分）。 */
    String FIXED = "FIXED";
    /** 按成交额比例，值是<b>万分比</b>（千分之一 = 10）。 */
    String RATIO = "RATIO";

    /**
     * @param goodsNo    商品；为空则跳过商品例外这一层
     * @param categoryNo 下单时快照的二级类目；为空则跳过类目这一层
     * @return 空表示<b>这两层都没配</b>，调用方该用平台兜底。
     *         <b>注意「配了 0」不是空</b> —— 储值卡配 0 分是一个明确决定，
     *         它必须返回 {@code EarnRule(FIXED, 0)} 而不是空，
     *         否则会掉到兜底层拿一个非 0 的值。这是这类多层配置最常见的 bug
     */
    Optional<EarnRule> ruleFor(String goodsNo, String categoryNo);

    /**
     * @param mode  {@link #FIXED} / {@link #RATIO}
     * @param value FIXED 时是分；RATIO 时是万分比。<b>整数，不用浮点</b> ——
     *              金额与比例一旦用 double，对账时的分位差没人说得清
     */
    record EarnRule(String mode, long value) {
    }
}
