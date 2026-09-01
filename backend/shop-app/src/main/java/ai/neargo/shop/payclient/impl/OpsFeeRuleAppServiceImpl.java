package ai.neargo.shop.payclient.impl;

import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.pay.dto.FeeRuleVO;
import ai.neargo.shop.pay.service.FeeRuleService;
import ai.neargo.shop.payclient.OpsFeeRuleAppService;
import ai.neargo.shop.spi.platform.AuditLogPort;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 费率的<b>内嵌</b>实现（{@code shop.pay.deployment} 不配或 = embedded）。
 *
 * <p>{@code matchIfMissing = true} —— <b>不配就是内嵌，也就是生产今天的样子</b>。
 * 反过来（不配就走远程）的话，任何一次漏配都会让主应用去调一个多半没起的进程，
 * 而症状是运营端所有费率相关的页面同时报错。
 *
 * <p>与 {@link RemoteOpsFeeRuleAppService} 互斥：两个 bean 同时在的话
 * Spring 报 NoUniqueBeanDefinition，那是启动期失败 —— <b>比运行期随机选一个好</b>。
 */
@Service
@ConditionalOnProperty(name = "shop.pay.deployment", havingValue = "embedded", matchIfMissing = true)
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
