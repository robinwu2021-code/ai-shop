package ai.neargo.shop.risk.api.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.risk.RiskService;
import ai.neargo.shop.risk.dto.BlacklistVO;
import ai.neargo.shop.risk.dto.RiskEventVO;
import ai.neargo.shop.risk.dto.RiskRuleVO;
import ai.neargo.shop.spi.platform.AuditLogPort;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 平台端 · 风控（P-16.2）。
 *
 * <p>⚠️ 这一版**不在下单/支付链路上做实时拦截**（TDD-运营端风控域 §二 D3）：
 * 拦截规则先做成配置 + 事件记录，实际拦截点另说。
 * {@code ErrorCode.RISK_BLOCKED(60001)} 至今没有任何生产调用方 ——
 * 那不是遗漏，是同一个判断在更早的时候已经做过一次。
 *
 * <p>四类动作：**看**（事件与黑名单列表）、**裁**（确认/排除，必写结论）、
 * **封**（拉黑，必须带原因与到期时间）、**配**（阈值与自动拦截意愿）。
 */
@Profile("ops")
@RestController
@Validated
public class OpsRiskController {

    private final RiskService riskService;
    private final AuditLogPort auditLogPort;

    public OpsRiskController(RiskService riskService, AuditLogPort auditLogPort) {
        this.riskService = riskService;
        this.auditLogPort = auditLogPort;
    }

    /**
     * 风险事件列表（P-16.2.1–3）。
     *
     * @param type   为空给全部；{@code FAKE_ORDER} / {@code ABNORMAL_FISSION} / {@code MALICIOUS_REFUND}
     * @param status {@code PENDING} / {@code CONFIRMED} / {@code DISMISSED}
     */
    @GetMapping("/ops/risk-events")
    @PreAuthorize("@perm.can('" + Perms.RISK_EVENT_READ + "')")
    public PageData<RiskEventVO> riskEvents(@RequestParam(required = false) String type,
                                            @RequestParam(required = false) String status,
                                            @RequestParam(required = false) String keyword,
                                            @RequestParam(defaultValue = "1") long page,
                                            @RequestParam(defaultValue = "50") long size) {
        return riskService.events(type, status, keyword, page, size);
    }

    /**
     * 事件处置。**确认与排除都要写结论** ——
     * 下次同一主体再命中时，得知道上次为什么放过。
     */
    @PostMapping("/ops/risk-events/{eventNo}/decide")
    @PreAuthorize("@perm.can('" + Perms.RISK_EVENT_HANDLE + "')")
    public RiskEventVO decideRiskEvent(@PathVariable String eventNo, @RequestBody DecideReq req) {
        RiskEventVO vo = riskService.decide(eventNo, req.confirmed() != null && req.confirmed(),
                req.verdict(), SecurityUtils.currentUserNo());
        auditLogPort.record("RISK_EVENT_DECIDE", eventNo, vo.status() + "｜" + vo.verdict());
        return vo;
    }

    /** @param activeOnly {@code "1"} 只看生效中的（与 ops-web 的 q 形状一致） */
    @GetMapping("/ops/blacklists")
    @PreAuthorize("@perm.can('" + Perms.RISK_BLACKLIST_READ + "')")
    public PageData<BlacklistVO> blacklists(@RequestParam(required = false) String subjectType,
                                            @RequestParam(required = false) String activeOnly,
                                            @RequestParam(required = false) String keyword,
                                            @RequestParam(defaultValue = "1") long page,
                                            @RequestParam(defaultValue = "50") long size) {
        return riskService.blacklists(subjectType, "1".equals(activeOnly), keyword, page, size);
    }

    /**
     * 拉黑（P-16.2.4）。**必须带原因与到期时间** ——
     * 无期限拉黑没有申诉出口，那是产品事故不是风控严格。
     */
    @PostMapping("/ops/blacklists")
    @PreAuthorize("@perm.can('" + Perms.RISK_BLACKLIST_UPDATE + "')")
    public BlacklistVO addBlacklist(@RequestBody AddBlacklistReq req) {
        BlacklistVO vo = riskService.addBlacklist(req.subjectType(), req.subject(), req.reason(),
                req.until(), SecurityUtils.currentUserNo());
        auditLogPort.record("BLACKLIST_ADD", vo.blackNo(),
                vo.subjectType() + "：" + vo.subject() + "｜" + vo.reason());
        return vo;
    }

    /**
     * 解禁申诉裁决。接受则解除拉黑，**记录保留**（留痕不是删除）。
     *
     * <p>⚠️ 方法名刻意不叫 {@code decideAppeal} —— 评价域（P-13.1.3）已经占了那个名字，
     * 那是「商家申诉差评」，这是「被拉黑者申诉解禁」，两件事。
     */
    @PostMapping("/ops/blacklists/{blackNo}/appeal")
    @PreAuthorize("@perm.can('" + Perms.RISK_BLACKLIST_UPDATE + "')")
    public BlacklistVO decideBlacklistAppeal(@PathVariable String blackNo,
                                             @RequestBody AppealReq req) {
        BlacklistVO vo = riskService.decideAppeal(blackNo, req.accept() != null && req.accept(),
                req.verdict(), SecurityUtils.currentUserNo());
        auditLogPort.record("BLACKLIST_APPEAL", blackNo, vo.appealStatus() + "｜" + vo.appealVerdict());
        return vo;
    }

    /**
     * 拦截规则（P-16.2.5）。返回**裸数组**（契约是 {@code RiskRule[]}，不是分页）。
     *
     * <p>三条读时自愈：迁移里的 INSERT 进不了测试库，靠它做种子的话规则列表永远是空的。
     */
    @GetMapping("/ops/risk-rules")
    @PreAuthorize("@perm.can('" + Perms.RISK_RULE_READ + "')")
    public List<RiskRuleVO> riskRules() {
        return riskService.rules();
    }

    /** 阈值必须 &gt; 0 —— 0 等于全量拦截，而页面上它只是一个普通数字。 */
    @PostMapping("/ops/risk-rules/{type}")
    @PreAuthorize("@perm.can('" + Perms.RISK_RULE_UPDATE + "')")
    public RiskRuleVO saveRiskRule(@PathVariable String type, @RequestBody SaveRuleReq req) {
        RiskRuleVO vo = riskService.saveRule(type, req.threshold() == null ? 0 : req.threshold(),
                req.autoBlock() != null && req.autoBlock(), SecurityUtils.currentUserNo());
        auditLogPort.record("RISK_RULE_SAVE", type,
                "阈值 " + vo.threshold() + "｜自动拦截 " + vo.autoBlock());
        return vo;
    }

    /**
     * @param verdict 处置结论。**字段名不叫 {@code reason}** ——
     *                {@code ops-reason-required} 守卫按 {@code String reason} 认「必填原因」，
     *                而 ops-web 发的是 {@code verdict}，同名会让那条守卫报假警
     */
    public record DecideReq(Boolean confirmed, String verdict) {
    }

    public record AddBlacklistReq(String subjectType, String subject, String reason, String until) {
    }

    public record AppealReq(Boolean accept, String verdict) {
    }

    public record SaveRuleReq(Integer threshold, Boolean autoBlock) {
    }
}
