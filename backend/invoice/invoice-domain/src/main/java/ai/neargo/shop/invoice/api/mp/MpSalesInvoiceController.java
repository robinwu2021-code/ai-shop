package ai.neargo.shop.invoice.api.mp;

import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.invoice.dto.InvoiceDtos.CarrierVO;
import ai.neargo.shop.invoice.dto.InvoiceDtos.ChoiceResultVO;
import ai.neargo.shop.invoice.dto.InvoiceDtos.InvoiceChoiceReq;
import ai.neargo.shop.invoice.dto.InvoiceDtos.InvoiceVO;
import ai.neargo.shop.invoice.dto.InvoiceDtos.SaveCarrierReq;
import ai.neargo.shop.invoice.service.InvoiceBuyerService;
import ai.neargo.shop.invoice.service.InvoiceService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * C 端电子发票：常用载具、给自己的订单选开法、「我的发票」（TDD-发票模块 §8）。
 *
 * <p><b>为什么入参里没有用户号，也没有「会员载具号码」</b>：买家身份取自登录态；会员载具的号码就是他的账号，
 * 由服务端填。让调用方报号码，等于允许把发票存进别人的会员载具。
 * 与旧的 {@code /mp/invoice/*}（线上订单事后申请平台开票，V94）是两回事，路径刻意不同。
 */
@Profile("api")
@RestController
@Validated
public class MpSalesInvoiceController {

    private final InvoiceBuyerService buyers;
    private final InvoiceService invoices;

    public MpSalesInvoiceController(InvoiceBuyerService buyers, InvoiceService invoices) {
        this.buyers = buyers;
        this.invoices = invoices;
    }

    @GetMapping("/mp/invoice-carriers")
    public List<CarrierVO> carriers() {
        var user = SecurityUtils.requireUser();
        return buyers.carriers(user.tenantNo(), user.userNo());
    }

    @PostMapping("/mp/invoice-carriers")
    public CarrierVO saveCarrier(@Valid @RequestBody SaveCarrierReq req) {
        var user = SecurityUtils.requireUser();
        return buyers.saveCarrier(user.tenantNo(), user.userNo(), req);
    }

    @PostMapping("/mp/invoice-carriers/{carrierNo}/delete")
    public void deleteCarrier(@PathVariable String carrierNo) {
        var user = SecurityUtils.requireUser();
        buyers.deleteCarrier(user.tenantNo(), user.userNo(), carrierNo);
    }

    @PutMapping("/mp/sales-invoices/choice")
    public List<ChoiceResultVO> choose(@Valid @RequestBody InvoiceChoiceReq req) {
        var user = SecurityUtils.requireUser();
        return buyers.choose(user.tenantNo(), user.userNo(), req);
    }

    @GetMapping("/mp/sales-invoices")
    public PageData<InvoiceVO> mine(@RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {
        var user = SecurityUtils.requireUser();
        return invoices.listForBuyer(user.tenantNo(), user.userNo(), page, size);
    }

    @GetMapping("/mp/sales-invoices/{invoiceNo}")
    public InvoiceVO detail(@PathVariable String invoiceNo) {
        var user = SecurityUtils.requireUser();
        return invoices.getForBuyer(user.tenantNo(), user.userNo(), invoiceNo);
    }
}
