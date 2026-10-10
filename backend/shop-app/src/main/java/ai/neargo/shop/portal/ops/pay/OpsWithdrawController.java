package ai.neargo.shop.portal.ops.pay;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.pay.dto.FinanceVOs.TaxRuleVO;
import ai.neargo.shop.payclient.OpsWithdrawAppService;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台端 · 提现审批与个税代扣规则（矩阵 P-12.2.1 / 12.2.2 / 12.2.3）。
 *
 * <p>ops-web 的 `/finance?tab=withdraw` 与 `?tab=invoice` 的税率卡此前<b>整块是 mock</b>：
 * 界面画完了、抽屉里连打款前置条件清单都摆好了，而后端一条端点都没有。
 *
 * <p>⚠️ <b>这里不打款。</b>通过后落 {@code APPROVED}，实际出款是线下动作
 * （待完成功能清单 B-12.5「一期只记账、线下结算」；分账参数书面口径见 B7）。
 * 界面上也<b>没有</b>「标记已打款」——那等于允许在钱没到账时把单子做平。
 */
@Profile("ops")
@RestController
@Validated
public class OpsWithdrawController {

    private final OpsWithdrawAppService app;

    public OpsWithdrawController(OpsWithdrawAppService app) {
        this.app = app;
    }

    /*
     * 提现单列表与审批两条端点 2026-10-09 撤掉（TDD-账期推进与放款记录 AC9 / PRD §7）：
     * 商家向平台提现是二清，入口从建成到撤掉生产 0 行。钱出去走 /ops/settle-batches/{no}/release。
     * 这个类只剩个税规则两条；WithdrawService 与 stl_withdraw 留着，删表另起迁移。
     */

    @GetMapping("/ops/finance/tax-rule")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_INVOICE_READ + "')")
    public TaxRuleVO taxRule() {
        return app.taxRule();
    }

    /**
     * 保存个税代扣规则。
     *
     * <p>改它会改变<b>所有后续提现</b>被扣掉多少，所以留痕不是可选项。
     */
    @PutMapping("/ops/finance/tax-rule")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_INVOICE_VERIFY + "')")
    public TaxRuleVO saveTaxRule(@RequestBody TaxRuleReq req) {
        return app.saveTaxRule(req.threshold(), req.rate());
    }

    /**
     * @param threshold 起征点（分）。包装类型：{@code null} 要能被当成缺参数，
     *                  而不是被 Jackson 当成 0 静默变成「从第一分钱就开始扣」
     * @param rate      税率（万分比）
     */
    public record TaxRuleReq(Long threshold, Long rate) {
    }
}
