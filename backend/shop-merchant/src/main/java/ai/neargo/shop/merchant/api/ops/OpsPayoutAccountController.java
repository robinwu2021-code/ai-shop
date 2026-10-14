package ai.neargo.shop.merchant.api.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.merchant.service.PayoutAccountService;
import ai.neargo.shop.merchant.service.PayoutAccountService.PayoutAccountVO;
import ai.neargo.shop.spi.platform.AuditLogPort;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
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
 * 平台端 · 供应商收款账户审核（ADR-011 自营供应商模式）。
 *
 * <p><b>为什么这件事必须要人核</b>：改收款账户等于把后续所有货款重定向到另一个账号。
 * 自助改完直接生效的话，拿到商家账号的人不需要动任何订单，
 * 只要换一张卡就能把下一期的钱领走 —— 而这个动作在业务日志里长得像一次正常的资料维护。
 *
 * <p>权限复用 {@link Perms#FINANCE_PAYOUT_EXECUTE}（付款执行）而不新开一个码：
 * 审核收款账户与执行付款是同一个岗位的一体两面，
 * 没有「只审账户不付款」或「只付款不审账户」的分工。
 */
@Profile("ops")
@RestController
@Validated
public class OpsPayoutAccountController {

    private final PayoutAccountService payoutAccounts;
    private final AuditLogPort auditLogPort;

    public OpsPayoutAccountController(PayoutAccountService payoutAccounts,
                                      AuditLogPort auditLogPort) {
        this.payoutAccounts = payoutAccounts;
        this.auditLogPort = auditLogPort;
    }

    /** 收款账户列表。默认全部，按状态筛「待审」就是审核队列。 */
    @GetMapping("/ops/payout-accounts")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_PAYOUT_EXECUTE + "')")
    public PageData<PayoutAccountVO> list(@RequestParam(required = false) String status,
                                          @RequestParam(required = false) String entityNo,
                                          @RequestParam(defaultValue = "1") long page,
                                          @RequestParam(defaultValue = "20") long size) {
        return payoutAccounts.list(status, entityNo, page, size);
    }

    /**
     * 审核。通过后这张卡立刻成为该主体的收款账户，同主体的旧账户被停用。
     *
     * <p><b>驳回必须写原因</b>，原样回商家 —— 不写等于让他猜。
     */
    @PostMapping("/ops/payout-accounts/{accountNo}/audit")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_PAYOUT_EXECUTE + "')")
    public PayoutAccountVO audit(@PathVariable String accountNo,
                                 @Valid @RequestBody AuditReq req) {
        PayoutAccountVO vo = payoutAccounts.audit(accountNo, req.pass(), req.remark());
        /*
         * **critical = true**：这是资金重定向，与「把钱批出去」同一量级。
         * 审计里记掩码与主体，不记账号 —— 账号进审计与进日志是同一类泄露。
         */
        auditLogPort.record("PAYOUT_ACCOUNT_AUDIT", accountNo,
                "%s 收款账户 %s（%s）".formatted(req.pass() ? "通过" : "驳回",
                        vo.accountMasked(), vo.entityNo()), true);
        return vo;
    }

    /**
     * @param pass   true 通过 / false 驳回
     * @param remark 驳回原因。<b>字段名叫 remark 不叫 reason</b> ——
     *               运营端契约与 {@code ops-reason-required} 守卫都按这个名字判定
     */
    public record AuditReq(@NotNull Boolean pass, String remark) {
    }
}
