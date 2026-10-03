package ai.neargo.shop.marketing.attribution.api.ops;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.IsoTime;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.marketing.attribution.AttributionRuleService;
import ai.neargo.shop.marketing.attribution.entity.MktAttributionLog;
import ai.neargo.shop.marketing.attribution.mapper.AttributionMappers.AttributionLogMapper;
import ai.neargo.shop.spi.platform.AuditLogPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 平台端 · 增长与归因（P-9.1）。
 *
 * <p><b>归因规则不是一张配置表，它直接决定商家付多少佣金</b>（ADR-004 §6：
 * 自带客流低费率/零佣金，平台客流正常费率）。所以这里的写操作一律 critical 留痕 ——
 * 商家质疑账单时，「谁在什么时候把优先级改了」必须查得到。
 *
 * <p>链路审计（P-9.1.3）读的是**真实的归因决策日志** {@code mkt_attribution_log}，
 * 不另造一份平行数据：另造的那份迟早与实际判定分岔，而分岔时商家看到的是
 * 「审计说算我的，账单说不算」。
 */
@Profile("ops")
@RestController
@Validated
public class OpsGrowthController {

    private final AttributionRuleService ruleService;
    private final AttributionLogMapper logMapper;
    private final ai.neargo.shop.marketing.attribution.FissionService fissionService;
    private final AuditLogPort auditLogPort;

    public OpsGrowthController(AttributionRuleService ruleService, AttributionLogMapper logMapper,
                               ai.neargo.shop.marketing.attribution.FissionService fissionService,
                               AuditLogPort auditLogPort) {
        this.ruleService = ruleService;
        this.logMapper = logMapper;
        this.fissionService = fissionService;
        this.auditLogPort = auditLogPort;
    }

    @GetMapping("/ops/attribution-rule")
    @PreAuthorize("@perm.can('" + Perms.GROWTH_ATTRIBUTION_READ + "')")
    public AttributionRuleService.RuleVO rule() {
        return ruleService.current();
    }

    /**
     * 保存归因规则。**改完下一单就生效** —— 引擎每次判定都读它。
     *
     * <p>四项校验各拦一种「存下去之后静默出错」：优先级缺项让来源无从裁决、
     * 窗口期 0 等于关掉归因（商家佣金翻倍）、策略非法、新客因子为空（新人券被无限领）。
     */
    @PostMapping("/ops/attribution-rule")
    @PreAuthorize("@perm.can('" + Perms.GROWTH_ATTRIBUTION_UPDATE + "')")
    public AttributionRuleService.RuleVO saveRule(@RequestBody SaveRuleReq req) {
        var vo = ruleService.save(new AttributionRuleService.SaveCommand(
                req.priority(), req.windowDays(), req.conflictPolicy(), req.newUserFactors()),
                SecurityUtils.currentUserNo());
        // critical：它决定商家的佣金档，改动必须能追到人
        auditLogPort.record("ATTRIBUTION_RULE", "MAIN",
                "优先级 " + String.join(">", vo.priority()) + "｜窗口 " + vo.windowDays()
                        + " 天｜冲突 " + vo.conflictPolicy(), true);
        return vo;
    }

    /**
     * 归因链路审计（P-9.1.3）。商家质疑「这单明明是我带来的」时的凭据。
     *
     * <p>`KEPT` 那种决策也在里面 —— 「为什么没算我的」与「为什么算了我的」
     * 是同样多的提问，只记成功的归因等于只能回答一半。
     */
    @GetMapping("/ops/attribution-traces")
    @PreAuthorize("@perm.can('" + Perms.GROWTH_ATTRIBUTION_READ + "')")
    public PageData<TraceVO> traces(@RequestParam(required = false) String userNo,
                                    @RequestParam(required = false) String merchantNo,
                                    @RequestParam(required = false) String source,
                                    @RequestParam(defaultValue = "1") long page,
                                    @RequestParam(defaultValue = "20") long size) {
        var w = Wrappers.<MktAttributionLog>lambdaQuery()
                .eq(userNo != null && !userNo.isBlank(), MktAttributionLog::getUserNo, userNo)
                .eq(merchantNo != null && !merchantNo.isBlank(), MktAttributionLog::getEntityNo, merchantNo)
                .eq(source != null && !source.isBlank(), MktAttributionLog::getSource, source)
                .orderByDesc(MktAttributionLog::getId);
        Page<MktAttributionLog> p = DataScopeContext.executeWithoutScope(() ->
                logMapper.selectPage(Page.of(page, Math.min(size, 100)), w));
        List<TraceVO> rows = p.getRecords().stream().map(OpsGrowthController::toTraceVO).toList();
        return PageData.of(rows, p.getTotal(), page, size);
    }

    private static TraceVO toTraceVO(MktAttributionLog l) {
        String ref = switch (l.getSource() == null ? "" : l.getSource()) {
            case "STORE_CODE" -> l.getEntityNo();
            case "INVITER" -> l.getInviterNo();
            case "CHANNEL" -> l.getChannel();
            default -> null;
        };
        return new TraceVO(String.valueOf(l.getId()), l.getUserNo(), l.getSource(), ref,
                l.getDecision(), l.getPrevSource(), l.getPrevRef(), l.getReason(),
                IsoTime.toIso(l.getAt()));
    }

    /**
     * @param decision  CREATED / REPLACED / KEPT —— **KEPT 是回答「为什么没算我的」的那一半**
     * @param prevSource 被覆盖/被保留的原来源，没有则空
     */
    public record TraceVO(String traceNo, String userNo, String source, String sourceRef,
                          String decision, String prevSource, String prevRef, String reason,
                          String attributedAt) {
    }

    public record SaveRuleReq(List<String> priority, Integer windowDays, String conflictPolicy,
                              List<String> newUserFactors) {
    }

    // ---------------------------------------------------------------- 裂变活动（P-9.2）

    @GetMapping("/ops/fission-campaigns")
    @PreAuthorize("@perm.can('" + Perms.GROWTH_FISSION_READ + "')")
    public PageData<ai.neargo.shop.marketing.attribution.FissionService.CampaignVO> fissions(
            @RequestParam(defaultValue = "false") boolean enabledOnly,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        return PageData.ofAll(fissionService.list(enabledOnly), page, size);
    }

    /** 建/改裂变活动。**奖励只能是券**，所以入参里没有奖励类型这一项。 */
    @PostMapping("/ops/fission-campaigns")
    @PreAuthorize("@perm.can('" + Perms.GROWTH_FISSION_UPDATE + "')")
    public ai.neargo.shop.marketing.attribution.FissionService.CampaignVO saveFission(
            @RequestBody SaveFissionReq req) {
        var vo = fissionService.save(
                new ai.neargo.shop.marketing.attribution.FissionService.SaveCommand(
                        req.fissionNo(), req.name(), req.couponNo(),
                        req.inviterCount(), req.inviteeCount()),
                SecurityUtils.currentUserNo());
        auditLogPort.record("FISSION_CAMPAIGN", vo.fissionNo(), vo.name());
        return vo;
    }

    /** 启停。启用时校验券仍可用 —— 指向停用券的活动会在发奖那一刻才失败。 */
    @PostMapping("/ops/fission-campaigns/{fissionNo}/enabled")
    @PreAuthorize("@perm.can('" + Perms.GROWTH_FISSION_UPDATE + "')")
    public ai.neargo.shop.marketing.attribution.FissionService.CampaignVO setFissionEnabled(
            @org.springframework.web.bind.annotation.PathVariable String fissionNo,
            @RequestBody EnabledReq req) {
        boolean on = Boolean.TRUE.equals(req.enabled());
        var vo = fissionService.setEnabled(fissionNo, on, SecurityUtils.currentUserNo());
        auditLogPort.record("FISSION_ENABLED", fissionNo, on ? "启用" : "停用");
        return vo;
    }

    public record SaveFissionReq(String fissionNo, String name, String couponNo,
                                 Integer inviterCount, Integer inviteeCount) {
    }

    public record EnabledReq(Boolean enabled) {
    }
}
