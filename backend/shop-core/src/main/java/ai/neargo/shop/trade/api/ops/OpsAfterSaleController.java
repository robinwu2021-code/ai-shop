package ai.neargo.shop.trade.api.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.spi.platform.AuditLogPort;
import ai.neargo.shop.trade.dto.OpsAfterSaleVO;

import java.util.List;
import ai.neargo.shop.trade.service.AfterSaleRuleService;
import ai.neargo.shop.trade.service.AfterSaleService;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台端 · 售后仲裁（P-6.1）。
 *
 * <p><b>ARBITRATING 此前只有入口没有出口</b>：用户能把争议上升到平台，
 * 而平台没有任何接口能裁 —— 单子停在那里，用户和商家都在等一个不会来的结果。
 * 与差评申诉是同一个形状的缺口。
 */
@Profile("ops")
@RestController
@Validated
public class OpsAfterSaleController {

    private final AfterSaleService afterSaleService;
    /**
     * 售后规则的唯一读写口。
     *
     * <p><b>这两条端点此前是悬空的</b>：自己拿 {@code SettingPort} 读写
     * {@code aftersale.fast-refund-rule}，而<b>没有任何业务代码读那个键</b> ——
     * {@code AfterSaleServiceImpl} 读的是它自己的 {@code @Value}。于是这一屏显示
     * 「关闭 · 上限 ¥20 · 24 小时内」，线上真正在跑的却是「无条件 · ¥100 · 不限时」。
     * 页面回读对得上、审计日志有记录、极速退照原样跑，没有一处会报错。
     */
    private final AfterSaleRuleService ruleService;
    private final AuditLogPort auditLogPort;

    public OpsAfterSaleController(AfterSaleService afterSaleService,
                                  AfterSaleRuleService ruleService, AuditLogPort auditLogPort) {
        this.afterSaleService = afterSaleService;
        this.ruleService = ruleService;
        this.auditLogPort = auditLogPort;
    }

    @GetMapping("/ops/after-sales")
    @PreAuthorize("@perm.can('" + Perms.AFTERSALE_TICKET_READ + "')")
    public ai.neargo.shop.common.PageData<OpsAfterSaleVO> list(@RequestParam(required = false) String status,
                                @RequestParam(required = false) String merchantNo,
                                @RequestParam(defaultValue = "1") long page,
                                @RequestParam(defaultValue = "20") long size) {
        // 运营端列表页按 {records,total} 渲染 —— 返回裸数组会被当成空页
        return ai.neargo.shop.common.PageData.ofAll(afterSaleService.opsList(status, merchantNo), page, size);
    }

    /**
     * 平台裁决。{@code refund=true} 支持用户（推进到退款），false 维持商家决定（关闭）。
     * <b>责任方与裁决说明都必填</b>。
     */
    @PostMapping("/ops/after-sales/{afterSaleNo}/decide")
    @PreAuthorize("@perm.can('" + Perms.AFTERSALE_TICKET_HANDLE + "')")
    public OpsAfterSaleVO decide(@PathVariable String afterSaleNo, @RequestBody DecideReq req) {
        boolean refund = Boolean.TRUE.equals(req.refund());
        var vo = afterSaleService.arbitrate(afterSaleNo, refund, req.liability(), req.verdict(),
                SecurityUtils.currentUserNo());
        // 裁决动的是真金白银，必须能追到是谁在什么时候裁的
        auditLogPort.record("AFTER_SALE_ARBITRATE", afterSaleNo,
                (refund ? "支持退款" : "维持商家决定") + "｜责任方 " + req.liability() + "｜" + req.verdict());
        return vo;
    }

    // ---------------------------------------------------------------- 售后规则（极速退 + 时效）

    @GetMapping("/ops/after-sales/fast-refund-rule")
    @PreAuthorize("@perm.can('" + Perms.AFTERSALE_REFUND_READ + "')")
    public AfterSaleRuleService.AfterSaleRuleVO fastRefundRule() {
        return ruleService.get();
    }

    /**
     * 保存售后规则：极速退的门槛，以及各环节的时效。
     *
     * <p>路径与四个原有字段名一个字都没动 —— 运营端那一屏正在用它们
     * （{@code lib/api/https/aftersale.ts}）。新增的四个时效字段是加上去的，不是换掉的。
     *
     * <p>校验在 {@link AfterSaleRuleService#save} 里：0 小时等于把功能关掉，
     * 而开关看起来还是开着的 —— 运营会以为极速退在跑，实际每一单都进了人工队列。
     * 时效那四个数被写成 0 更糟：{@code replyHours=0} 意味着每一笔申请下一分钟就自动同意。
     */
    @PostMapping("/ops/after-sales/fast-refund-rule")
    @PreAuthorize("@perm.can('" + Perms.AFTERSALE_REFUND_APPROVE + "')")
    public AfterSaleRuleService.AfterSaleRuleVO saveFastRefundRule(@RequestBody RuleReq req) {
        var saved = ruleService.save(req.mergeInto(ruleService.get()), SecurityUtils.currentUserNo());
        // 这份规则决定多少钱可以**不经人工**退出去、以及商家沉默多久就替他同意，改动必须留痕
        auditLogPort.record("FAST_REFUND_RULE", "aftersale.fast-refund-rule",
                "%s｜上限 %d 分｜下单 %d 小时内｜商家 %d 小时未处理自动同意｜寄回 %d 天｜确认 %d 小时"
                        .formatted(saved.enabled() ? "开启" : "关闭", saved.maxAmount(),
                                saved.withinHours(), saved.replyHours(), saved.shipBackDays(),
                                saved.confirmHours()));
        return saved;
    }

    /**
     * 请求体。<b>每个字段都是包装类型，缺的字段保持原值</b>。
     *
     * <p>不直接收 {@link AfterSaleRuleService.AfterSaleRuleVO}：那是 record，
     * Jackson 对缺组件是硬拒（{@code HttpMessageNotReadableException} → 400）。
     * 而运营端那一屏此刻只发四个字段（{@code enabled/maxAmount/withinHours/categories}）——
     * 直接收 VO 会让<b>它的保存按钮当场 400</b>，而这次改动本该只是往里加字段。
     *
     * <p>顺带得到的是「改一格存一格」：以后拆成两屏（极速退 / 时效）也不必互相带着对方的值。
     */
    public record RuleReq(Boolean enabled, Long maxAmount, Integer withinHours,
                          List<String> categories, Integer replyHours, Integer shipBackDays,
                          Integer confirmHours, Integer interveneWorkDays) {

        AfterSaleRuleService.AfterSaleRuleVO mergeInto(AfterSaleRuleService.AfterSaleRuleVO cur) {
            return new AfterSaleRuleService.AfterSaleRuleVO(
                    enabled == null ? cur.enabled() : enabled,
                    maxAmount == null ? cur.maxAmount() : maxAmount,
                    withinHours == null ? cur.withinHours() : withinHours,
                    categories == null ? cur.categories() : categories,
                    replyHours == null ? cur.replyHours() : replyHours,
                    shipBackDays == null ? cur.shipBackDays() : shipBackDays,
                    confirmHours == null ? cur.confirmHours() : confirmHours,
                    interveneWorkDays == null ? cur.interveneWorkDays() : interveneWorkDays,
                    null, null);
        }
    }

    /** @param liability PLATFORM / MERCHANT / PICKUP */
    public record DecideReq(Boolean refund, String liability, String verdict) {
    }

}
