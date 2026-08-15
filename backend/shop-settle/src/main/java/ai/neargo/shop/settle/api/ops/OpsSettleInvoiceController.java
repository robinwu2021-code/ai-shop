package ai.neargo.shop.settle.api.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.settle.dto.FinanceVOs.SettleInvoiceVO;
import ai.neargo.shop.settle.service.SettleInvoiceService;
import ai.neargo.shop.spi.platform.AuditLogPort;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台端 · 商家结算发票（矩阵 P-12.2.4 结算凭证/发票）。
 *
 * <p><b>路径是 {@code /ops/finance/invoices}，与另外两条发票端点是三件事</b>：
 * {@code /ops/purchase-invoices} 是供应商开给平台的进项票，
 * {@code /ops/invoice-requests} 是平台开给消费者的销项票。
 * 三者的开票方、受票方、税务后果都不同 —— 看着像重复，其实一条都不能省。
 */
@Profile("ops")
@RestController
@Validated
public class OpsSettleInvoiceController {

    private final SettleInvoiceService invoiceService;
    private final AuditLogPort auditLogPort;

    public OpsSettleInvoiceController(SettleInvoiceService invoiceService,
                                      AuditLogPort auditLogPort) {
        this.invoiceService = invoiceService;
        this.auditLogPort = auditLogPort;
    }

    /** 返回 {@code PageData}：运营端列表页按 {@code {records,total}} 渲染。 */
    @GetMapping("/ops/finance/invoices")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_INVOICE_READ + "')")
    public PageData<SettleInvoiceVO> list(@RequestParam(required = false) String status,
                                          @RequestParam(required = false) String keyword,
                                          @RequestParam(defaultValue = "1") long page,
                                          @RequestParam(defaultValue = "20") long size) {
        return invoiceService.list(status, keyword, page, size);
    }

    /** 开票。三道校验防的都是虚开，见 {@link SettleInvoiceService#issue}。 */
    @PostMapping("/ops/finance/invoices/{invoiceNo}/issue")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_INVOICE_VERIFY + "')")
    public SettleInvoiceVO issue(@PathVariable String invoiceNo, @RequestBody IssueReq req) {
        String operator = SecurityUtils.currentUserNo();
        SettleInvoiceVO vo = invoiceService.issue(invoiceNo, req.serialNo(), operator);
        // 手工开票必须留痕，否则事后查不到是谁开的
        auditLogPort.record("SETTLE_INVOICE_ISSUE", invoiceNo, "流水号 " + vo.serialNo(), true);
        return vo;
    }

    @PostMapping("/ops/finance/invoices/{invoiceNo}/reject")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_INVOICE_VERIFY + "')")
    public SettleInvoiceVO reject(@PathVariable String invoiceNo, @RequestBody RejectReq req) {
        String operator = SecurityUtils.currentUserNo();
        SettleInvoiceVO vo = invoiceService.reject(invoiceNo, req.reason(), operator);
        auditLogPort.record("SETTLE_INVOICE_REJECT", invoiceNo, req.reason());
        return vo;
    }

    /** @param serialNo 发票流水号，必填 —— 没有它的「已开票」等于没开 */
    public record IssueReq(String serialNo) {
    }

    /** @param reason 驳回原因，必填。<b>原样回商家 B 端</b> */
    public record RejectReq(String reason) {
    }
}
