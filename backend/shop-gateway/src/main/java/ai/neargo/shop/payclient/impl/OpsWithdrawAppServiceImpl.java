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
