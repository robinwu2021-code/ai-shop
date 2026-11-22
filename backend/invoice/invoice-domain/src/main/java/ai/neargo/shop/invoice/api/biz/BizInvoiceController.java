package ai.neargo.shop.invoice.api.biz;

import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.invoice.dto.InvoiceDtos.AllowanceReq;
import ai.neargo.shop.invoice.dto.InvoiceDtos.BuyerPreferenceReq;
import ai.neargo.shop.invoice.dto.InvoiceDtos.InvoiceQuery;
import ai.neargo.shop.invoice.dto.InvoiceDtos.InvoiceVO;
import ai.neargo.shop.invoice.dto.InvoiceDtos.ProviderCheckVO;
import ai.neargo.shop.invoice.dto.InvoiceDtos.UploadBacklogVO;
import ai.neargo.shop.invoice.dto.InvoiceDtos.IssueInvoiceReq;
import ai.neargo.shop.invoice.dto.InvoiceDtos.VoidInvoiceReq;
import ai.neargo.shop.invoice.service.InvoiceService;
import ai.neargo.shop.invoice.service.InvoiceTransmissionService;
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
    private final InvoiceTransmissionService transmissions;

    public BizInvoiceController(InvoiceService invoices, InvoiceTransmissionService transmissions) {
        this.invoices = invoices;
        this.transmissions = transmissions;
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

    /** 本门店上传积压：待发、暂停、待人工、即将到期、已逾期（列表页顶部提示）。 */
    @PreAuthorize("@perm.canBiz('" + BizPerms.INVOICE_READ + "')")
    @GetMapping("/biz/invoices/upload-backlog")
    public UploadBacklogVO uploadBacklog() {
        return transmissions.backlog(BizInvoiceScope.current());
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

    @PreAuthorize("@perm.canBiz('" + BizPerms.INVOICE_VOID + "')")
    @PostMapping("/biz/invoices/{invoiceNo}/allowances/{allowanceNo}/void")
    public InvoiceVO voidAllowance(@PathVariable String invoiceNo, @PathVariable String allowanceNo,
            @Valid @RequestBody VoidInvoiceReq req) {
        return invoices.voidAllowance(BizInvoiceScope.current(), invoiceNo, allowanceNo, req.reason());
    }

    /**
     * 向加值中心（鲸跃）查这张票的状态并对账：服务商已收到而本地未标送达的上传补记为已送达；
     * 不一致且无待补传时告警。会改上传任务状态，所以是 POST、要开票权限。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.INVOICE_ISSUE + "')")
    @PostMapping("/biz/invoices/{invoiceNo}/provider-checks")
    public ProviderCheckVO checkWithProvider(@PathVariable String invoiceNo) {
        return transmissions.checkWithProvider(BizInvoiceScope.current(), invoiceNo);
    }

    /**
     * 重传被服务商拒收、标为「待人工处理」的上传任务（开立 / 作废 / 折让）。报文不变，原样再发；
     * 用于修正了服务商侧问题（或确认是对方临时故障）之后。账号问题造成的暂停不走这里，核验账号即恢复。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.INVOICE_ISSUE + "')")
    @PostMapping("/biz/invoices/{invoiceNo}/retransmissions")
    public InvoiceVO retransmit(@PathVariable String invoiceNo) {
        var scope = BizInvoiceScope.current();
        invoices.get(scope, invoiceNo);   // 门店范围校验：不是本店的票直接 404
        transmissions.retry(scope, invoiceNo);
        return invoices.get(scope, invoiceNo);
    }
}
