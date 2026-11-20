package ai.neargo.shop.payclient.impl;

import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.pay.dto.FinanceVOs.RefundSplitBackVO;
import ai.neargo.shop.pay.service.RefundSplitBackService;
import ai.neargo.shop.payclient.OpsRefundSplitBackAppService;
import ai.neargo.shop.spi.platform.AuditLogPort;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class OpsRefundSplitBackAppServiceImpl implements OpsRefundSplitBackAppService {

    private final RefundSplitBackService service;
    private final AuditLogPort auditLogPort;

    public OpsRefundSplitBackAppServiceImpl(RefundSplitBackService service,
                                            AuditLogPort auditLogPort) {
        this.service = service;
        this.auditLogPort = auditLogPort;
    }

    @Override
    public List<RefundSplitBackVO> pending() {
        return service.pending();
    }

    @Override
    public RefundSplitBackVO execute(String afterSaleNo) {
        String operator = SecurityUtils.currentUserNo();
        RefundSplitBackVO vo = service.execute(afterSaleNo, operator);
        auditLogPort.record("REFUND_SPLIT_BACK", afterSaleNo,
                "回退分账并退款 " + vo.refundMinor() + " 分", true);
        return vo;
    }
}
