package ai.neargo.shop.portal.ops.pay;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.payclient.OpsPayoutListAppService;
import ai.neargo.shop.payclient.OpsPayoutListAppService.PayoutListVO;
import ai.neargo.shop.spi.platform.AuditLogPort;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台端 · 付款清单（ADR-011 · P-12.1）。
 *
 * <p>财务拿它去网银转账，回来在应付账款页回填流水号。
 * <b>这是整条自营资金链上钱真正离开平台的那一步。</b>
 *
 * <p><b>响应里带明文账号</b>（只有这一个接口带）。所以：
 * <ul>
 *   <li>权限判 {@link Perms#FINANCE_PAYOUT_EXECUTE} —— 与登记付款同一个码</li>
 *   <li><b>每次调用都写 critical 审计</b>，记谁、什么时候、导了多少钱多少家。
 *       导出不是读操作：账号一旦离开系统就无法收回，而「谁导过」是事后唯一的线索</li>
 *   <li>审计里<b>不记账号</b> —— 账号进审计与进日志是同一类泄露</li>
 * </ul>
 */
@Profile("ops")
@RestController
@Validated
public class OpsPayoutListController {

    private final OpsPayoutListAppService app;
    private final AuditLogPort auditLogPort;

    public OpsPayoutListController(OpsPayoutListAppService app, AuditLogPort auditLogPort) {
        this.app = app;
        this.auditLogPort = auditLogPort;
    }

    /** @param entityNo 只导某一家，空则全部 */
    @GetMapping("/ops/payables/payout-list")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_PAYOUT_EXECUTE + "')")
    public PayoutListVO payoutList(@RequestParam(required = false) String entityNo) {
        PayoutListVO vo = app.list(entityNo);
        auditLogPort.record("PAYOUT_LIST_EXPORT",
                entityNo == null || entityNo.isBlank() ? "ALL" : entityNo,
                "导出付款清单：%d 家 · %d 分；另有 %d 条因条件不足未进清单"
                        .formatted(vo.rows().size(), vo.totalMinor(), vo.blocked().size()),
                true);
        return vo;
    }
}
