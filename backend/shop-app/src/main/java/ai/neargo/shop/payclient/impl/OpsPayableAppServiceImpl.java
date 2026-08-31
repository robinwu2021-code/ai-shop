package ai.neargo.shop.payclient.impl;

import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.pay.SettleService;
import ai.neargo.shop.pay.dto.PurchaseInvoiceVO;
import ai.neargo.shop.pay.dto.SettleBillVO;
import ai.neargo.shop.payclient.OpsPayableAppService;
import ai.neargo.shop.spi.platform.AuditLogPort;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class OpsPayableAppServiceImpl implements OpsPayableAppService {

    private final SettleService settleService;
    private final AuditLogPort auditLogPort;

    public OpsPayableAppServiceImpl(SettleService settleService, AuditLogPort auditLogPort) {
        this.settleService = settleService;
        this.auditLogPort = auditLogPort;
    }

    @Override
    public List<SettleBillVO> list(String status, String entityNo) {
        return settleService.opsPayables(status, entityNo);
    }

    @Override
    public SettleBillVO confirm(String settleNo) {
        String operator = SecurityUtils.currentUserNo();
        SettleBillVO vo = settleService.confirmRecon(settleNo, operator);
        auditLogPort.record("PAYABLE_CONFIRM", settleNo, "对账确认，应付 " + vo.netMinor() + " 分");
        return vo;
    }

    @Override
    public SettleBillVO markPaid(String settleNo, String paymentRef) {
        String operator = SecurityUtils.currentUserNo();
        SettleBillVO vo = settleService.markPaid(settleNo, paymentRef, operator);
        // 钱出账的登记必须留痕：事后追责靠的就是「谁在什么时候登记了哪张凭证」
        auditLogPort.record("PAYABLE_PAID", settleNo,
                "凭证 " + paymentRef + "｜金额 " + vo.netMinor() + " 分");
        return vo;
    }

    @Override
    public SettleBillVO markNoInvoice(String settleNo, String reason) {
        String operator = SecurityUtils.currentUserNo();
        SettleBillVO vo = settleService.markNoInvoice(settleNo, reason, operator);
        auditLogPort.record("PAYABLE_NO_INVOICE", settleNo, reason);
        return vo;
    }

    @Override
    public List<PurchaseInvoiceVO> invoices(String status) {
        return settleService.opsInvoices(status);
    }

    @Override
    public PurchaseInvoiceVO verifyInvoice(String invoiceNo) {
        String operator = SecurityUtils.currentUserNo();
        PurchaseInvoiceVO vo = settleService.verifyInvoice(invoiceNo, operator);
        auditLogPort.record("INVOICE_VERIFY", invoiceNo,
                "核验通过｜" + vo.titleName() + "｜" + vo.amountMinor() + " 分");
        return vo;
    }

    @Override
    public PurchaseInvoiceVO rejectInvoice(String invoiceNo, String reason) {
        String operator = SecurityUtils.currentUserNo();
        PurchaseInvoiceVO vo = settleService.rejectInvoice(invoiceNo, reason, operator);
        auditLogPort.record("INVOICE_REJECT", invoiceNo, reason);
        return vo;
    }

    @Override
    public Map<String, String> invoiceTitle() {
        return settleService.platformInvoiceTitle();
    }

    @Override
    public Map<String, String> saveInvoiceTitle(Map<String, String> fields) {
        var saved = settleService.savePlatformInvoiceTitle(fields, SecurityUtils.currentUserNo());
        auditLogPort.record("INVOICE_TITLE", "finance.invoice-title", saved.get("companyName"));
        return saved;
    }
}
