package ai.neargo.shop.payclient.impl;

import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.pay.PayoutService;
import ai.neargo.shop.payclient.OpsPayoutAppService;
import ai.neargo.shop.spi.platform.AuditLogPort;
import org.springframework.stereotype.Service;

import java.util.List;

// 不限 profile：与同域的 OpsPayoutList/OpsSettleStats 一致 —— 只有 Controller 挂 @Profile("ops")
@Service
public class OpsPayoutAppServiceImpl implements OpsPayoutAppService {
    private final PayoutService payoutService;
    private final AuditLogPort auditLogPort;

    public OpsPayoutAppServiceImpl(PayoutService payoutService, AuditLogPort auditLogPort) {
        this.payoutService = payoutService;
        this.auditLogPort = auditLogPort;
    }

    @Override
    public List<PayoutService.PayoutVO> list(String status, String entityNo) {
        return payoutService.list(status, entityNo);
    }

    @Override
    public PayoutService.PayoutVO markPaid(String payoutNo, String paymentRef) {
        String operator = SecurityUtils.currentUserNo();
        PayoutService.PayoutVO vo = payoutService.markPaid(payoutNo, paymentRef, operator);
        // 钱出账的登记必须留痕：事后追责靠的就是「谁在什么时候登记了哪张凭证」
        auditLogPort.record("PAYOUT_PAID", payoutNo,
                "凭证 " + paymentRef + "｜金额 " + vo.amountMinor() + " 分｜" + vo.billCount() + " 单", true);
        return vo;
    }

    @Override
    public PayoutService.PayoutVO markFailed(String payoutNo, String reason) {
        String operator = SecurityUtils.currentUserNo();
        PayoutService.PayoutVO vo = payoutService.markFailed(payoutNo, reason, operator);
        auditLogPort.record("PAYOUT_FAILED", payoutNo, "退回：" + reason + "｜批次 " + vo.batchNo() + " 回到可放款", true);
        return vo;
    }
}
