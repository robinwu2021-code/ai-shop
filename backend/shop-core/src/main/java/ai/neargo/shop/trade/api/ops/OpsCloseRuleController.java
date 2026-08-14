package ai.neargo.shop.trade.api.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.spi.platform.AuditLogPort;
import ai.neargo.shop.trade.service.CloseRuleService;
import ai.neargo.shop.trade.service.CloseRuleService.CloseRuleVO;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台端 · 关单策略（P-4.2.3，页面 {@code /orders?tab=close}）。
 *
 * <p><b>这两条端点此前不存在</b>，而页面是完整的：读得回来（读的是 mock）、能编辑、
 * 有保存按钮 —— 保存点下去 404，页面什么都不说。
 *
 * <p><b>路径与字段照抄 ops-web 的既有调用</b>（{@code lib/api/https/payment.ts} 与
 * {@code lib/types/payment.ts}），不另起一套。上一个提交刚因为「后端改了形状、
 * 前端契约没跟上」让财务页整页崩过 —— 那次是形状分叉，这次从一开始就对齐。
 *
 * <p><b>PUT 而不是 POST</b>：同样是照抄前端（{@code client.put}）。
 * 这条配置是整份覆盖写，PUT 的语义也更准。
 */
@Profile("ops")
@RestController
@Validated
public class OpsCloseRuleController {

    private final CloseRuleService closeRuleService;
    private final AuditLogPort auditLogPort;

    public OpsCloseRuleController(CloseRuleService closeRuleService, AuditLogPort auditLogPort) {
        this.closeRuleService = closeRuleService;
        this.auditLogPort = auditLogPort;
    }

    /**
     * 读。挂 {@link Perms#ORDER_READ} 而不是 {@code ORDER_MODIFY} ——
     * 这一页运营和客服都要看得到「现在配的是多久」，看不代表能改。
     * 读写分开是权限码细化的第一条原则。
     */
    @GetMapping("/ops/payments/close-rule")
    @PreAuthorize("@perm.can('" + Perms.ORDER_READ + "')")
    public CloseRuleVO closeRule() {
        return closeRuleService.get();
    }

    /**
     * 写。
     *
     * <p>写审计而不是只留 {@code updatedBy}：这个数与掉单**直接因果** ——
     * 调短了会把正在付款的人关掉。事后复盘「那天的掉单是不是因为有人改了这里」，
     * 要查得到是谁在什么时候从多少改到多少，而 {@code updatedBy} 只留得下最后一次。
     */
    @PutMapping("/ops/payments/close-rule")
    @PreAuthorize("@perm.can('" + Perms.ORDER_MODIFY + "')")
    public CloseRuleVO saveCloseRule(@RequestBody SaveReq req) {
        int before = closeRuleService.unpaidMinutes();
        CloseRuleVO saved = closeRuleService.save(req.unpaidMinutes(), req.remindBeforeMinutes(),
                Boolean.TRUE.equals(req.autoRefundOnLateCallback()), SecurityUtils.currentUserNo());
        auditLogPort.record("CLOSE_RULE_UPDATED", KEY_TARGET,
                "关单时限 %d → %d 分钟；提醒提前 %d 分钟；迟到回调自动退款=%s"
                        .formatted(before, saved.unpaidMinutes(), saved.remindBeforeMinutes(),
                                saved.autoRefundOnLateCallback()));
        return saved;
    }

    /** 审计对象不是某一笔订单，是这份全局配置本身 */
    private static final String KEY_TARGET = "trade.close-rule";

    /**
     * {@code autoRefundOnLateCallback} 用包装类型 {@code Boolean}：
     * 前端漏传时 {@code boolean} 会静默变成 false，而 false 恰好是一个**合法且危险**的值
     * （关掉自动退款）。用包装类型至少能在这里分得出「传了 false」与「没传」。
     */
    public record SaveReq(int unpaidMinutes, int remindBeforeMinutes,
                          Boolean autoRefundOnLateCallback) {
    }
}
