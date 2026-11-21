package ai.neargo.shop.invoice.api.biz;

import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.invoice.dto.InvoiceDtos.AllowanceReq;
import ai.neargo.shop.invoice.dto.InvoiceDtos.BuyerPreferenceReq;
import ai.neargo.shop.invoice.dto.InvoiceDtos.InvoiceQuery;
import ai.neargo.shop.invoice.dto.InvoiceDtos.InvoiceVO;
import ai.neargo.shop.invoice.dto.InvoiceDtos.IssueInvoiceReq;
import ai.neargo.shop.invoice.dto.InvoiceDtos.VoidInvoiceReq;
import ai.neargo.shop.invoice.service.InvoiceService;
import ai.neargo.shop.spi.invoice.InvoicePort.Buyer;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * B 端发票：按来源单开票、查询、作废、折让（TDD-发票模块 §2.1）。
 *
 * <p><b>为什么开票请求只收来源单号和买受人</b>：金额与明细由发票域向来源所有者读冻结事实，
 * 不让收银端决定发票开多少钱；主体与门店取自登录上下文，入参里不接受主体号。
 * 证明联打印与补印走打印中心 {@code /biz/print/requests}（来源类型 INVOICE），这里不重复一套。
 */
@Profile("api")
@RestController
@Validated
public class BizInvoiceController {

    private final InvoiceService invoices;

    public BizInvoiceController(InvoiceService invoices) {
        this.invoices = invoices;
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.INVOICE_ISSUE + "')")
    @PostMapping("/biz/invoices")
    public InvoiceVO issue(@Valid @RequestBody IssueInvoiceReq req) {
        var scope = BizInvoiceScope.current();
        var b = req.buyer();
        var buyer = b == null ? Buyer.consumer()
                : new Buyer(b.taxId(), b.name(), b.carrierType(), b.carrierId(), b.donateCode());
        var receipt = invoices.issue(scope, req.sourceType(), req.sourceNo(), buyer);
        return invoices.get(scope, receipt.invoiceNo());
    }

    /** 付款前记下买受人（载具 / 统编 / 捐赠）；主体打开自动开立时，付款成功即按它开票。 */
    @PreAuthorize("@perm.canBiz('" + BizPerms.INVOICE_ISSUE + "')")
    @PutMapping("/biz/invoices/buyer-preferences")
    public void rememberBuyer(@Valid @RequestBody BuyerPreferenceReq req) {
        var b = req.buyer();
        invoices.rememberBuyer(BizInvoiceScope.current(), req.sourceType(), req.sourceNo(),
                new Buyer(b.taxId(), b.name(), b.carrierType(), b.carrierId(), b.donateCode()));
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.INVOICE_READ + "')")
    @GetMapping("/biz/invoices")
    public PageData<InvoiceVO> list(@Valid @ModelAttribute InvoiceQuery query) {
        return invoices.list(BizInvoiceScope.current(), query);
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.INVOICE_READ + "')")
    @GetMapping("/biz/invoices/{invoiceNo}")
    public InvoiceVO get(@PathVariable String invoiceNo) {
        return invoices.get(BizInvoiceScope.current(), invoiceNo);
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.INVOICE_VOID + "')")
    @PostMapping("/biz/invoices/{invoiceNo}/void")
    public InvoiceVO voidInvoice(@PathVariable String invoiceNo, @Valid @RequestBody VoidInvoiceReq req) {
        return invoices.voidInvoice(BizInvoiceScope.current(), invoiceNo, req.reason());
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.INVOICE_VOID + "')")
    @PostMapping("/biz/invoices/{invoiceNo}/allowances")
    public InvoiceVO allowance(@PathVariable String invoiceNo, @Valid @RequestBody AllowanceReq req) {
        return invoices.allowance(BizInvoiceScope.current(), invoiceNo, req.amountMinor(), req.reason());
    }
}
