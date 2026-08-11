package ai.neargo.shop.settle.api.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.settle.entity.StlFeeRule;
import ai.neargo.shop.settle.service.FeeRuleService;
import ai.neargo.shop.spi.platform.AuditLogPort;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台端 · 费率规则（落地清单 P1-4）。
 *
 * <p>费率此前写在 {@code application.yml} 里，<b>改一次要改配置文件加重启</b>。
 * 这里把它变成可运营的表，且<b>只增不改</b>——调费率是插一个新版本，
 * 旧版本永久保留，因为真正会被问到的是「上个月那批单当时按什么费率算的」。
 */
@Profile("ops")
@RestController
@Validated
public class OpsFeeRuleController {

    private final FeeRuleService feeRuleService;
    private final AuditLogPort auditLogPort;

    public OpsFeeRuleController(FeeRuleService feeRuleService, AuditLogPort auditLogPort) {
        this.feeRuleService = feeRuleService;
        this.auditLogPort = auditLogPort;
    }

    /** 全部版本，含历史。运营要能看见「什么时候调过、调成什么、为什么调」。 */
    @GetMapping("/ops/settle/fee-rules")
    @PreAuthorize("@perm.can('" + Perms.SETTLE_MANAGE + "')")
    public List<StlFeeRule> rules() {
        return feeRuleService.rules();
    }

    /**
     * 某时刻实际生效的四格费率。
     *
     * <p>单独开这个而不是让端上自己从版本列表里推：
     * 「哪一版此刻在生效」牵涉停用回退的语义，端上推错了不会报错，只会显示错。
     */
    @GetMapping("/ops/settle/fee-rules/effective")
    @PreAuthorize("@perm.can('" + Perms.SETTLE_MANAGE + "')")
    public Map<String, Integer> effective(@RequestParam(required = false) Long at) {
        return feeRuleService.effectiveRates(at == null ? System.currentTimeMillis() : at);
    }

    @PostMapping("/ops/settle/fee-rules")
    @PreAuthorize("@perm.can('" + Perms.SETTLE_MANAGE + "')")
    public StlFeeRule add(@RequestBody AddReq req) {
        String operator = SecurityUtils.currentUserNo();
        long from = req.effectiveFrom() == null ? System.currentTimeMillis() : req.effectiveFrom();
        StlFeeRule rule = feeRuleService.addRule(req.businessMode(), req.trafficSource(),
                req.rateBp() == null ? 0 : req.rateBp(), from, req.remark(), operator);
        auditLogPort.record("FEE_RULE_ADD",
                req.businessMode() + "/" + req.trafficSource() + "=" + req.rateBp(), operator);
        return rule;
    }

    /**
     * @param effectiveFrom 毫秒；<b>留空 = 立即生效</b>，填未来时刻 = 预约生效
     * @param rateBp        包装类型：{@code null} 要能被当成缺参数报出来，
     *                      而不是被 Jackson 当成 0 静默变成「零费率」
     */
    public record AddReq(String businessMode, String trafficSource, Integer rateBp,
                         Long effectiveFrom, String remark) {
    }
}
