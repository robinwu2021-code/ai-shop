package ai.neargo.shop.platform.api.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.platform.OpsService;
import ai.neargo.shop.platform.PlatformConfigService;
import ai.neargo.shop.platform.PlatformConfigService.AppearanceVO;
import ai.neargo.shop.platform.PlatformConfigService.FeatureFlagVO;
import ai.neargo.shop.platform.PlatformConfigService.MarketVO;
import ai.neargo.shop.platform.PlatformConfigService.RuleTextsVO;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 平台端 · 系统与配置（P-17.1）。
 *
 * <p>路径与入参<b>照抄 ops-web 现有调用</b>（`lib/api/https/system.ts`），
 * 不另起一套 —— 今晚已经因为「两边各写一套」踩过路径单复数与裸数组两次，
 * 症状统一是「接口 200、页面空白」。
 *
 * <p>权限用 {@link Perms#PLATFORM_CONFIG} 而不是 {@code industry:manage}：
 * 这四类改的是<b>全平台的行为</b>（皮肤、灰度、协议文案、汇率），
 * 与「哪些行业能开小微」不是一个量级。
 *
 * <p>同一页面上的 {@code auth-codes} / {@code industries} / {@code service-scopes}
 * 后端本来就有，在 {@link OpsPlatformController}，不在这里。
 */
@Profile("ops")
@RestController
@Validated
public class OpsPlatformConfigController {

    private final PlatformConfigService configService;
    private final OpsService opsService;

    public OpsPlatformConfigController(PlatformConfigService configService, OpsService opsService) {
        this.configService = configService;
        this.opsService = opsService;
    }

    // ---------------------------------------------------------------- 皮肤

    @GetMapping("/ops/appearance")
    @PreAuthorize("@perm.can('" + Perms.PLATFORM_CONFIG + "')")
    public AppearanceVO appearance() {
        return configService.appearance();
    }

    @PostMapping("/ops/appearance")
    @PreAuthorize("@perm.can('" + Perms.PLATFORM_CONFIG + "')")
    public AppearanceVO saveAppearance(@RequestBody AppearanceReq req) {
        AppearanceVO vo = configService.saveAppearance(req.defaultSkin(), req.festivalSkin(),
                req.festivalFrom(), req.festivalTo(), req.fallbackLang(),
                SecurityUtils.currentUserNo());
        // 皮肤下发影响所有 C 端用户，属于「三个月后要能查是谁改的」那一类
        opsService.audit("PLATFORM_APPEARANCE", req.defaultSkin(),
                "节日皮肤=" + req.festivalSkin() + "｜回落语言=" + req.fallbackLang());
        return vo;
    }

    // ---------------------------------------------------------------- 功能开关

    @GetMapping("/ops/feature-flags")
    @PreAuthorize("@perm.can('" + Perms.PLATFORM_CONFIG + "')")
    public List<FeatureFlagVO> featureFlags() {
        return configService.featureFlags();
    }

    @PostMapping("/ops/feature-flags/{key}")
    @PreAuthorize("@perm.can('" + Perms.PLATFORM_CONFIG + "')")
    public List<FeatureFlagVO> saveFeatureFlag(@PathVariable String key,
                                               @RequestBody FlagReq req) {
        List<FeatureFlagVO> all = configService.saveFeatureFlag(key,
                Boolean.TRUE.equals(req.enabled()),
                req.rolloutPercent() == null ? 0 : req.rolloutPercent(),
                SecurityUtils.currentUserNo());
        opsService.audit("PLATFORM_FLAG", key, req.enabled() + "｜灰度=" + req.rolloutPercent());
        return all;
    }

    // ---------------------------------------------------------------- 规则文案

    @GetMapping("/ops/rule-texts")
    @PreAuthorize("@perm.can('" + Perms.PLATFORM_CONFIG + "')")
    public RuleTextsVO ruleTexts() {
        return configService.ruleTexts();
    }

    /** 改文案会**留下上一版**：用户同意的是某一版协议，覆盖了就查不回来。 */
    @PostMapping("/ops/rule-texts")
    @PreAuthorize("@perm.can('" + Perms.PLATFORM_CONFIG + "')")
    public RuleTextsVO saveRuleTexts(@RequestBody RuleTextsReq req) {
        RuleTextsVO vo = configService.saveRuleTexts(req.refund(), req.pickup(), req.weighDiff(),
                SecurityUtils.currentUserNo());
        opsService.audit("PLATFORM_RULE_TEXTS", "v" + vo.version(), "规则文案更新");
        return vo;
    }

    /** 历史版本，新的在前。纠纷时靠它回答「他当时同意的是什么」。 */
    @GetMapping("/ops/rule-texts/history")
    @PreAuthorize("@perm.can('" + Perms.PLATFORM_CONFIG + "')")
    public List<RuleTextsVO> ruleTextsHistory() {
        return configService.ruleTextsHistory();
    }

    // ---------------------------------------------------------------- 市场

    @GetMapping("/ops/markets")
    @PreAuthorize("@perm.can('" + Perms.PLATFORM_CONFIG + "')")
    public List<MarketVO> markets() {
        return configService.markets();
    }

    @PostMapping("/ops/markets/{code}")
    @PreAuthorize("@perm.can('" + Perms.PLATFORM_CONFIG + "')")
    public List<MarketVO> saveMarketRate(@PathVariable String code, @RequestBody MarketReq req) {
        List<MarketVO> all = configService.saveMarketRate(code,
                req.rate() == null ? 0d : req.rate(), Boolean.TRUE.equals(req.enabled()),
                SecurityUtils.currentUserNo());
        // 汇率直接换算价格，改动必须留痕
        opsService.audit("PLATFORM_MARKET_RATE", code, "汇率=" + req.rate() + "｜启用=" + req.enabled());
        return all;
    }

    // ---------------------------------------------------------------- 入参

    public record AppearanceReq(String defaultSkin, String festivalSkin, String festivalFrom,
                                String festivalTo, String fallbackLang) {
    }

    public record FlagReq(Boolean enabled, Integer rolloutPercent) {
    }

    public record RuleTextsReq(String refund, String pickup, String weighDiff) {
    }

    public record MarketReq(Double rate, Boolean enabled) {
    }
}
