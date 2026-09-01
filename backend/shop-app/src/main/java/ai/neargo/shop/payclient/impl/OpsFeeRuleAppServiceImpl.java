package ai.neargo.shop.payclient.impl;

import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.pay.dto.FeeRuleVO;
import ai.neargo.shop.pay.service.FeeRuleService;
import ai.neargo.shop.payclient.OpsFeeRuleAppService;
import ai.neargo.shop.spi.platform.AuditLogPort;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class OpsFeeRuleAppServiceImpl implements OpsFeeRuleAppService {

    private final FeeRuleService feeRuleService;
    private final AuditLogPort auditLogPort;

    public OpsFeeRuleAppServiceImpl(FeeRuleService feeRuleService, AuditLogPort auditLogPort) {
        this.feeRuleService = feeRuleService;
        this.auditLogPort = auditLogPort;
    }

    @Override
    public List<FeeRuleVO> rules() {
        return feeRuleService.rules();
    }

    @Override
    public Map<String, Integer> effectiveRates(Long at) {
        return feeRuleService.effectiveRates(at == null ? System.currentTimeMillis() : at);
    }

    @Override
    public FeeRuleVO add(String businessMode, String trafficSource, Integer rateBp,
                         Long effectiveFrom, String remark) {
        String operator = SecurityUtils.currentUserNo();
        long from = effectiveFrom == null ? System.currentTimeMillis() : effectiveFrom;
        FeeRuleVO rule = feeRuleService.addRule(businessMode, trafficSource,
                rateBp == null ? 0 : rateBp, from, remark, operator);
        auditLogPort.record("FEE_RULE_ADD",
                businessMode + "/" + trafficSource + "=" + rateBp, operator);
        return rule;
    }
}
