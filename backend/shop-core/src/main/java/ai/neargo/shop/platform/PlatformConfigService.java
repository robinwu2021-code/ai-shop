package ai.neargo.shop.platform;

import java.util.List;

/**
 * 平台自身的四类配置（P-17.1）：皮肤下发 · 功能开关与灰度 · 规则文案 · 市场与汇率。
 *
 * <p><b>不建表</b>：四类各占 {@code sys_setting} 一行。它们都是「一组 JSON、
 * 整体读写、低频修改」，为此各建一张表只会多四份增删改查。
 *
 * <p><b>{@code updatedAt} / {@code updatedBy} 来自 {@code sys_setting} 的列，
 * 不塞进 JSON</b> —— 塞进去就有两份，而两份必然对不上（谁改的以哪份为准？）。
 *
 * <p>校验逐条对齐 ops-web 的 mock（`lib/api/mocks/system.ts`）。
 * <b>后端不能比 mock 宽</b>：mock 上点不通的路径，指向真后端也该点不通，
 * 反过来才是「mock 比后端好看」那类缺陷的来源。
 */
public interface PlatformConfigService {

    // ---------------------------------------------------------------- 皮肤

    AppearanceVO appearance();

    /**
     * 下发 C 端皮肤。
     *
     * <p>合法皮肤清单存在 {@code sys_setting} 的 {@code platform.skins} 里，
     * <b>前端也读它</b> —— 在后端另抄一份 {@code SKINS} 常量的话，两份必然分叉，
     * 而分叉的表现是「界面显示已下发商务蓝，用户那边回落到默认值」。
     */
    AppearanceVO saveAppearance(String defaultSkin, String festivalSkin,
                                String festivalFrom, String festivalTo, String fallbackLang,
                                String operatorNo);

    // ---------------------------------------------------------------- 功能开关

    List<FeatureFlagVO> featureFlags();

    /**
     * 改开关与灰度比例（0–100）。
     *
     * <p>⚠️ <b>本批只做配置的读写</b>：拨了开关不等于哪段代码会跟着变 ——
     * 让某个 flag 真的控制一段逻辑，是那段逻辑自己的事。
     * 运营端已就此标注，免得有人关了开关以为功能停了。
     */
    List<FeatureFlagVO> saveFeatureFlag(String key, boolean enabled, int rolloutPercent,
                                        String operatorNo);

    // ---------------------------------------------------------------- 规则文案

    RuleTextsVO ruleTexts();

    /**
     * 改规则文案。三条都不能为空 —— C 端要展示给用户看。
     *
     * <p><b>保存前把旧版追加进历史</b>：用户同意的是<b>某一版</b>协议，
     * 覆盖之后「他当时同意的是什么」永远查不回来，而那正是纠纷时唯一有用的东西。
     */
    RuleTextsVO saveRuleTexts(String refund, String pickup, String weighDiff, String operatorNo);

    /** 文案历史，新的在前。 */
    List<RuleTextsVO> ruleTextsHistory();

    // ---------------------------------------------------------------- 市场与汇率

    List<MarketVO> markets();

    /**
     * 改某个市场的汇率与开关。
     *
     * <p><b>基准货币的汇率恒为 1 且不可改</b> —— 改了整套价格换算的原点就没了。
     */
    List<MarketVO> saveMarketRate(String code, double rate, boolean enabled, String operatorNo);

    // ---------------------------------------------------------------- VO
    //
    // 字段名逐个对齐 ops-web 的类型（AppearanceConfig / FeatureFlag / RuleTexts /
    // MarketConfig）。今晚已经踩过两次命名错配，症状都是「接口 200、页面空白」。

    /**
     * @param festivalSkin 留空 = 不启用节日皮肤
     * @param fallbackLang 缺译时回落到哪个语言（R9）
     */
    record AppearanceVO(String defaultSkin, String festivalSkin, String festivalFrom,
                        String festivalTo, String fallbackLang,
                        String updatedAt, String updatedBy) {
    }

    /** @param rolloutPercent 灰度比例 0–100。{@code enabled} 为假时它不生效 */
    record FeatureFlagVO(String key, String name, boolean enabled, int rolloutPercent,
                         String updatedAt) {
    }

    /** @param version 版本号，从 1 起。历史里靠它定位「他当时同意的是哪一版」 */
    record RuleTextsVO(String refund, String pickup, String weighDiff,
                       int version, String updatedAt, String updatedBy) {
    }

    /**
     * @param rate    对基准货币的汇率。基准货币恒为 1
     * @param enabled 关掉后该市场的商品不再售卖
     */
    record MarketVO(String code, String name, String currency, String timezone,
                    double rate, boolean enabled) {
    }
}
