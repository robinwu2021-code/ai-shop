package ai.neargo.shop.portal.ops.pay;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.pay.PayoutService;
import ai.neargo.shop.payclient.OpsPayoutAppService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
 * 平台端 · 放款记录（V391 / TDD-账期推进与放款记录 批 2）。
 *
 * <p>一笔网银转账一条。凭证号与银行流水都挂在它上面 —— 此前挂在逐张结算单上，
 * 财务一笔转账要逐张回填同一个号。
 *
 * <p>资源段用<b>复数</b>（/ops 的约定）。生成放款在 {@code /ops/settle-batches/{no}/release}，
 * 这里只有看、登记、退回三件事。
 */
@Profile("ops")
@RestController
@Validated
public class OpsPayoutController {
    private final OpsPayoutAppService app;

    public OpsPayoutController(OpsPayoutAppService app) {
        this.app = app;
    }

    /** @param status 空 = 全部；{@code PENDING} 待导出、{@code EXPORTED} 已导出待登记 */
    @GetMapping("/ops/payouts")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_SETTLE_READ + "')")
    public List<PayoutService.PayoutVO> list(@RequestParam(required = false) String status,
                                             @RequestParam(required = false) String entityNo) {
        return app.list(status, entityNo);
    }

    /** 登记凭证。凭证号必填：没有凭证号的「已付」事后对不上银行流水，也说不清是谁付的 */
    @PostMapping("/ops/payouts/{payoutNo}/paid")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_PAYOUT_EXECUTE + "')")
    public PayoutService.PayoutVO paid(@PathVariable String payoutNo, @Valid @RequestBody PaidReq req) {
        return app.markPaid(payoutNo, req.paymentRef());
    }

    /** 打款失败或退回。原因必填 —— 退回的钱要能再放一次，而不是留一笔永远「已付」的空账 */
    @PostMapping("/ops/payouts/{payoutNo}/fail")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_PAYOUT_EXECUTE + "')")
    public PayoutService.PayoutVO fail(@PathVariable String payoutNo, @Valid @RequestBody FailReq req) {
        return app.markFailed(payoutNo, req.reason());
    }

    public record PaidReq(@NotBlank String paymentRef) {
    }

    public record FailReq(@NotBlank String reason) {
    }
}
