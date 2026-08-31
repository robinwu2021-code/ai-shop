package ai.neargo.shop.portal.ops.pay;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.settle.dto.FinanceVOs.TaxRuleVO;
import ai.neargo.shop.settle.dto.FinanceVOs.WithdrawVO;
import ai.neargo.shop.settle.service.WithdrawService;
import ai.neargo.shop.spi.platform.AuditLogPort;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
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

    private final WithdrawService withdrawService;
    private final AuditLogPort auditLogPort;

    public OpsWithdrawController(WithdrawService withdrawService, AuditLogPort auditLogPort) {
        this.withdrawService = withdrawService;
        this.auditLogPort = auditLogPort;
    }

    /**
     * 提现单列表。
     *
     * <p>返回 {@code PageData} 而不是裸数组：运营端列表页按 {@code {records,total}} 渲染，
     * 裸数组会被当成空页 —— 接口 200、数据几十条、页面显示「暂无数据」。
     */
    @GetMapping("/ops/finance/withdrawals")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_WITHDRAW_APPROVE + "')")
    public PageData<WithdrawVO> list(@RequestParam(required = false) String status,
                                     @RequestParam(required = false) String keyword,
                                     @RequestParam(defaultValue = "1") long page,
                                     @RequestParam(defaultValue = "20") long size) {
        return withdrawService.list(status, keyword, page, size);
    }

    /**
     * 审批一笔提现。<b>运营端唯一会把钱批出去的动作</b>，六道校验见
     * {@link WithdrawService#decide}。
     */
    @PostMapping("/ops/finance/withdrawals/{withdrawNo}/decide")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_WITHDRAW_APPROVE + "')")
    public WithdrawVO decide(@PathVariable String withdrawNo, @RequestBody DecideReq req) {
        String operator = SecurityUtils.currentUserNo();
        boolean pass = Boolean.TRUE.equals(req.pass());
        WithdrawVO vo = withdrawService.decide(withdrawNo, pass, req.remark(), operator);
        // 动的是真金白银，必须能追到是谁在什么时候批的
        auditLogPort.record("WITHDRAW_DECIDE", withdrawNo,
                (pass ? "通过" : "驳回") + "｜" + (req.remark() == null ? "" : req.remark()), true);
        return vo;
    }

    @GetMapping("/ops/finance/tax-rule")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_INVOICE_READ + "')")
    public TaxRuleVO taxRule() {
        return withdrawService.taxRule();
    }

    /**
     * 保存个税代扣规则。
     *
     * <p>改它会改变<b>所有后续提现</b>被扣掉多少，所以留痕不是可选项。
     */
    @PutMapping("/ops/finance/tax-rule")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_INVOICE_VERIFY + "')")
    public TaxRuleVO saveTaxRule(@RequestBody TaxRuleReq req) {
        String operator = SecurityUtils.currentUserNo();
        TaxRuleVO vo = withdrawService.saveTaxRule(
                req.threshold() == null ? 0L : req.threshold(),
                req.rate() == null ? 0L : req.rate(), operator);
        auditLogPort.record("TAX_RULE_SAVE", "finance.tax-rule",
                "起征点 %d 分｜税率 %d 万分比".formatted(vo.threshold(), vo.rate()), true);
        return vo;
    }

    /**
     * @param pass   true 通过 / false 驳回
     * @param remark 驳回原因 / 大额复核说明。<b>字段名不叫 reason</b> ——
     *               运营端契约发的是 {@code remark}（`ops-reason-required` 守卫按字段名判定，
     *               叫 reason 会要求前端也发 reason，而它发的是 remark）
     */
    public record DecideReq(Boolean pass, String remark) {
    }

    /**
     * @param threshold 起征点（分）。包装类型：{@code null} 要能被当成缺参数，
     *                  而不是被 Jackson 当成 0 静默变成「从第一分钱就开始扣」
     * @param rate      税率（万分比）
     */
    public record TaxRuleReq(Long threshold, Long rate) {
    }
}
