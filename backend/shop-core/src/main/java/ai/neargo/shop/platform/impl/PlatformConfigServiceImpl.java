package ai.neargo.shop.platform.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.platform.PlatformConfigService;
import ai.neargo.shop.platform.SettingService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 四类平台配置的读写。
 *
 * <p>每一类的默认值都写在这里：<b>没配过时返回默认值而不是报错</b> ——
 * 参数表少一行不该让整个页面打不开（与 {@code SettingService} 的既有口径一致）。
 */
@Service
public class PlatformConfigServiceImpl implements PlatformConfigService {

    static final String KEY_APPEARANCE = "platform.appearance";
    static final String KEY_FLAGS = "platform.feature-flags";
    static final String KEY_RULE_TEXTS = "platform.rule-texts";
    static final String KEY_RULE_TEXTS_HISTORY = "platform.rule-texts.history";
    static final String KEY_MARKETS = "platform.markets";

    /**
     * 可下发给 C 端的皮肤。<b>存在配置里而不是写成常量</b> ——
     * 前端 {@code C_END_THEMES} 是同一份清单，写两份必然分叉，
     * 而分叉的表现是「运营端显示已下发，用户那边回落到默认值」。
     */
    static final String KEY_SKINS = "platform.skins";

    /** 与前端 {@code C_END_THEMES} 同源：排除运营端专有的 business */
    private static final String DEFAULT_SKINS = "[\"mono\",\"fresh\",\"promo\",\"blue\"]";

    /** 基准货币：汇率恒为 1，不可改 —— 改了整套价格换算的原点就没了 */
    private static final String BASE_CURRENCY = "CNY";

    private static final String DEFAULT_APPEARANCE =
            "{\"defaultSkin\":\"fresh\",\"fallbackLang\":\"zh-CN\"}";

    /**
     * 没配过时的开关清单。
     *
     * <p><b>从 `[]` 改成带默认项</b>：空清单意味着运营端那一页什么都没有，
     * 于是这套机制建好之后一直没人用过。开关是**代码里读的东西** ——
     * 代码里读哪几个，这里就该列哪几个，运营才知道有什么可开。
     *
     * <p>`group.audit` 默认 **false（建团即上线）**：这是加开关之前的行为，
     * 默认值必须与它逐字相同 —— 否则升个版本，所有商家开的团突然都不上线了，
     * 而没有任何人收到通知。要审的平台自己打开。
     *
     * <p>`category.gate.enforce` 默认 **false（只展示、不限制）**：受理入口刚铺开
     * （B 端传证 + 运营按证授码），存量商家的授权码还在补。这时候闸门拦住的
     * 不是无证经营，是平台自己还没建好的那条路。它同时管两条路：
     * 商品上架、门店摆货架。
     */
    private static final String DEFAULT_FLAGS = """
            [{"key":"category.gate.enforce","name":"类目资质校验",\
            "enabled":false,"rolloutPercent":0,"updatedAt":null},\
            {"key":"goods.audit","name":"商品上架审核",\
            "enabled":true,"rolloutPercent":0,"updatedAt":null},\
            {"key":"group.audit","name":"拼团上线审核",\
            "enabled":false,"rolloutPercent":0,"updatedAt":null}]""";

    private static final String DEFAULT_RULE_TEXTS =
            "{\"refund\":\"\",\"pickup\":\"\",\"weighDiff\":\"\",\"version\":0}";

    private static final String DEFAULT_MARKETS = """
            [{"code":"CN","name":"中国大陆","currency":"CNY","timezone":"Asia/Shanghai",\
            "rate":1.0,"enabled":true}]""";

    private final SettingService settingService;
    /** 市场主数据在 pay 域（S11）—— 币种与账期口径是资金域的知识 */
    private final ai.neargo.shop.spi.pay.MarketPort marketService;
    private final ObjectMapper json;

    public PlatformConfigServiceImpl(SettingService settingService, ObjectMapper json, ai.neargo.shop.spi.pay.MarketPort marketService) {
        this.marketService = marketService;
        this.settingService = settingService;
        this.json = json;
    }

    // ---------------------------------------------------------------- 皮肤

    @Override
    public AppearanceVO appearance() {
        Map<String, Object> m = readMap(KEY_APPEARANCE, DEFAULT_APPEARANCE);
        return new AppearanceVO(str(m, "defaultSkin"), str(m, "festivalSkin"),
                str(m, "festivalFrom"), str(m, "festivalTo"), str(m, "fallbackLang"),
                str(m, "updatedAt"), str(m, "updatedBy"));
    }

    @Override
    @Transactional
    public AppearanceVO saveAppearance(String defaultSkin, String festivalSkin, String festivalFrom,
                                       String festivalTo, String fallbackLang, String operatorNo) {
        List<String> skins = readList(KEY_SKINS, DEFAULT_SKINS);
        if (defaultSkin == null || !skins.contains(defaultSkin)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        if (notBlank(festivalSkin) && !skins.contains(festivalSkin)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        /*
         * 节日皮肤的结束必须晚于开始。不校验的话，一个区间倒挂的配置会
         * **永远不生效**，而页面上看着是配好的 —— 到了节日当天才发现没换皮。
         */
        if (notBlank(festivalFrom) && notBlank(festivalTo)
                && festivalTo.compareTo(festivalFrom) <= 0) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("defaultSkin", defaultSkin);
        m.put("festivalSkin", blankToNull(festivalSkin));
        m.put("festivalFrom", blankToNull(festivalFrom));
        m.put("festivalTo", blankToNull(festivalTo));
        m.put("fallbackLang", notBlank(fallbackLang) ? fallbackLang : "zh-CN");
        stamp(m, operatorNo);
        settingService.put(KEY_APPEARANCE, json.writeValueAsString(m), operatorNo);
        return appearance();
    }

    // ---------------------------------------------------------------- 功能开关

    @Override
    public List<FeatureFlagVO> featureFlags() {
        return readValue(KEY_FLAGS, DEFAULT_FLAGS, new TypeReference<List<FeatureFlagVO>>() {
        });
    }

    @Override
    @Transactional
    public List<FeatureFlagVO> saveFeatureFlag(String key, boolean enabled, int rolloutPercent,
                                               String operatorNo) {
        if (key == null || key.isBlank() || rolloutPercent < 0 || rolloutPercent > 100) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        String now = Instant.now().toString();
        List<FeatureFlagVO> all = new ArrayList<>(featureFlags());
        int idx = indexOfKey(all, key);
        FeatureFlagVO updated = new FeatureFlagVO(key,
                idx >= 0 ? all.get(idx).name() : key, enabled, rolloutPercent, now);
        if (idx >= 0) {
            all.set(idx, updated);
        } else {
            // 开关是**代码里读的东西**，运营端只能改已存在的那些。
            // 但这里允许新增：不允许的话，加一个开关就要发一次版，
            // 而那正是 feature flag 要解决的问题
            all.add(updated);
        }
        settingService.put(KEY_FLAGS, json.writeValueAsString(all), operatorNo);
        return all;
    }

    private static int indexOfKey(List<FeatureFlagVO> all, String key) {
        for (int i = 0; i < all.size(); i++) {
            if (key.equals(all.get(i).key())) {
                return i;
            }
        }
        return -1;
    }

    // ---------------------------------------------------------------- 规则文案

    @Override
    public RuleTextsVO ruleTexts() {
        Map<String, Object> m = readMap(KEY_RULE_TEXTS, DEFAULT_RULE_TEXTS);
        return new RuleTextsVO(str(m, "refund"), str(m, "pickup"), str(m, "weighDiff"),
                intOf(m, "version"), str(m, "updatedAt"), str(m, "updatedBy"));
    }

    @Override
    @Transactional
    public RuleTextsVO saveRuleTexts(String refund, String pickup, String weighDiff,
                                     String operatorNo) {
        // 三条都不能为空：C 端要展示给用户看，空文案等于页面上一片空白
        if (!notBlank(refund) || !notBlank(pickup) || !notBlank(weighDiff)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        RuleTextsVO current = ruleTexts();
        /*
         * 先把旧版追加进历史，再写新版。
         *
         * 用户同意的是**某一版**协议，覆盖之后「他当时同意的是什么」永远查不回来 ——
         * 而那正是纠纷时唯一有用的东西。version 从 1 起，0 表示从来没配过。
         */
        if (current.version() > 0) {
            List<RuleTextsVO> history = new ArrayList<>(ruleTextsHistory());
            history.add(0, current);
            settingService.put(KEY_RULE_TEXTS_HISTORY, json.writeValueAsString(history), operatorNo);
        }
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("refund", refund);
        m.put("pickup", pickup);
        m.put("weighDiff", weighDiff);
        m.put("version", current.version() + 1);
        stamp(m, operatorNo);
        settingService.put(KEY_RULE_TEXTS, json.writeValueAsString(m), operatorNo);
        return ruleTexts();
    }

    @Override
    public List<RuleTextsVO> ruleTextsHistory() {
        return readValue(KEY_RULE_TEXTS_HISTORY, "[]", new TypeReference<List<RuleTextsVO>>() {
        });
    }

    // ---------------------------------------------------------------- 市场

    /**
     * 市场清单。<b>2026-09-02（S11）起读的是 {@code sys_market} 表，不再是那段 JSON。</b>
     *
     * <p>为什么换：JSON 存得下，但<b>无法被引用与约束</b> ——
     * {@code market} 这个列早就在五张表上用着（商品 SKU、门店价、
     * 积分账户、积分流水、积分池），却没有任何东西保证那些值真的存在。
     * 写错一个市场码，积分会记进一个不存在的市场，<b>而不报错</b>。
     *
     * <p><b>返回形状一个字段都没改</b> —— ops-web 那一页不用动。
     * 换存储不该让调用方跟着改，那正是这层接口存在的理由。
     */
    @Override
    public List<MarketVO> markets() {
        return marketService.all().stream()
                .map(m -> new MarketVO(m.market(), m.name(), m.currency(),
                        m.timeZone(), m.displayRate(), m.enabled()))
                .toList();
    }

    @Override
    @Transactional
    public List<MarketVO> saveMarketRate(String code, double rate, boolean enabled,
                                         String operatorNo) {
        var row = marketService.find(code).orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND));
        /*
         * 两条校验原样保留 —— 换存储不该顺手放宽规则。
         *
         * 基准货币的汇率是换算原点，改了整套价格都失去参照；
         * 汇率非正会让折算出来的价格是 0 或负数。
         */
        if (BASE_CURRENCY.equals(row.currency()) && rate != 1.0d) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        if (rate <= 0) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        marketService.saveRate(code, rate, enabled, operatorNo);
        return markets();
    }

    // ---------------------------------------------------------------- 助手

    private Map<String, Object> readMap(String key, String defaultJson) {
        return readValue(key, defaultJson, new TypeReference<Map<String, Object>>() {
        });
    }

    private List<String> readList(String key, String defaultJson) {
        return readValue(key, defaultJson, new TypeReference<List<String>>() {
        });
    }

    /** 解析失败退回默认值：一行写坏的配置不该让整个页面打不开 */
    private <T> T readValue(String key, String defaultJson, TypeReference<T> type) {
        try {
            return json.readValue(settingService.get(key, defaultJson), type);
        } catch (RuntimeException e) {
            return json.readValue(defaultJson, type);
        }
    }

    /**
     * 时间戳与操作人。
     *
     * <p>与 {@code sys_setting} 的 {@code updated_at/by} 重复，但前端要的是
     * VO 里的字段，而 {@code SettingService} 只暴露值不暴露那两列 ——
     * 收窄那个接口是更大的一次改动，本批先在 JSON 里带一份。
     * <b>真源仍是表上的列</b>，这里这份只用于展示。
     */
    private static void stamp(Map<String, Object> m, String operatorNo) {
        m.put("updatedAt", Instant.now().toString());
        m.put("updatedBy", operatorNo);
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : String.valueOf(v);
    }

    private static int intOf(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v instanceof Number n ? n.intValue() : 0;
    }

    private static boolean notBlank(String v) {
        return v != null && !v.isBlank();
    }

    private static String blankToNull(String v) {
        return notBlank(v) ? v : null;
    }
}
