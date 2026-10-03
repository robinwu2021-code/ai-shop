package ai.neargo.shop.payclient.impl;

import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.pay.dto.FinanceVOs.TaxRuleVO;
import ai.neargo.shop.pay.dto.FinanceVOs.WithdrawVO;
import ai.neargo.shop.pay.service.WithdrawService;
import ai.neargo.shop.payclient.OpsWithdrawAppService;
import ai.neargo.shop.spi.platform.AuditLogPort;
import org.springframework.stereotype.Service;

@Service
public class OpsWithdrawAppServiceImpl implements OpsWithdrawAppService {

    private final WithdrawService withdrawService;
    private final AuditLogPort auditLogPort;

    public OpsWithdrawAppServiceImpl(WithdrawService withdrawService, AuditLogPort auditLogPort) {
        this.withdrawService = withdrawService;
        this.auditLogPort = auditLogPort;
    }

    @Override
    public PageData<WithdrawVO> list(String status, String keyword, long page, long size) {
        return withdrawService.list(status, keyword, page, size);
    }

    @Override
    public WithdrawVO decide(String withdrawNo, Boolean pass, String remark) {
        String operator = SecurityUtils.currentUserNo();
        /*
         * `pass` 漏传时按**不通过**算。反过来写的话，一个缺字段的请求
         * 就是一次放行 —— 而这是运营端唯一会把钱批出去的动作。
         */
        boolean passed = Boolean.TRUE.equals(pass);
        WithdrawVO vo = withdrawService.decide(withdrawNo, passed, remark, operator);
        // 动的是真金白银，必须能追到是谁在什么时候批的
        auditLogPort.record("WITHDRAW_DECIDE", withdrawNo,
                (passed ? "通过" : "驳回") + "｜" + (remark == null ? "" : remark), true);
        return vo;
    }

    @Override
    public TaxRuleVO taxRule() {
        return withdrawService.taxRule();
    }

    @Override
    public TaxRuleVO saveTaxRule(Long threshold, Long rate) {
        String operator = SecurityUtils.currentUserNo();
        TaxRuleVO vo = withdrawService.saveTaxRule(
                threshold == null ? 0L : threshold, rate == null ? 0L : rate, operator);
        auditLogPort.record("TAX_RULE_SAVE", "finance.tax-rule",
                "起征点 %d 分｜税率 %d 万分比".formatted(vo.threshold(), vo.rate()), true);
        return vo;
    }
}
