package ai.neargo.shop.payclient.impl;

import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.pay.dto.FinanceVOs.SettleInvoiceVO;
import ai.neargo.shop.pay.service.SettleInvoiceService;
import ai.neargo.shop.payclient.OpsSettleInvoiceAppService;
import ai.neargo.shop.spi.platform.AuditLogPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** 内嵌实现。与 {@link RemoteOpsSettleInvoiceAppService} 互斥；不配开关就是内嵌 */
@Service
@ConditionalOnProperty(name = "shop.pay.deployment", havingValue = "embedded", matchIfMissing = true)
public class OpsSettleInvoiceAppServiceImpl implements OpsSettleInvoiceAppService {

    private final SettleInvoiceService invoiceService;
    private final AuditLogPort auditLogPort;

    public OpsSettleInvoiceAppServiceImpl(SettleInvoiceService invoiceService,
                                          AuditLogPort auditLogPort) {
        this.invoiceService = invoiceService;
        this.auditLogPort = auditLogPort;
    }

    @Override
    public PageData<SettleInvoiceVO> list(String status, String keyword, long page, long size) {
        return invoiceService.list(status, keyword, page, size);
    }

    @Override
    public SettleInvoiceVO issue(String invoiceNo, String serialNo) {
        String operator = SecurityUtils.currentUserNo();
        SettleInvoiceVO vo = invoiceService.issue(invoiceNo, serialNo, operator);
        // 手工开票必须留痕，否则事后查不到是谁开的
        auditLogPort.record("SETTLE_INVOICE_ISSUE", invoiceNo, "流水号 " + vo.serialNo(), true);
        return vo;
    }

    @Override
    public SettleInvoiceVO reject(String invoiceNo, String reason) {
        String operator = SecurityUtils.currentUserNo();
        SettleInvoiceVO vo = invoiceService.reject(invoiceNo, reason, operator);
        auditLogPort.record("SETTLE_INVOICE_REJECT", invoiceNo, reason);
        return vo;
    }
}
