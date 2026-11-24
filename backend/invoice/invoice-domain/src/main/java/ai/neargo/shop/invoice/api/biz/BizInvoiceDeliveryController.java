package ai.neargo.shop.invoice.api.biz;

import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.invoice.service.InvoiceDeliveryService;
import ai.neargo.shop.invoice.service.InvoiceDeliveryService.Delivery;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Profile("api")
@ConditionalOnProperty(name = "shop.invoice.delivery.enabled", havingValue = "true")
@RestController
@Validated
public class BizInvoiceDeliveryController {
    private final InvoiceDeliveryService deliveries;

    public BizInvoiceDeliveryController(InvoiceDeliveryService deliveries) { this.deliveries = deliveries; }

    @PreAuthorize("@perm.canBiz('" + BizPerms.INVOICE_READ + "')")
    @GetMapping("/biz/invoices/{invoiceNo}/deliveries")
    public PageData<Delivery> deliveries(@PathVariable @Size(max = 64) String invoiceNo,
            @RequestParam(defaultValue = "1") @Min(1) @Max(10000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return deliveries.list(BizInvoiceScope.current(), invoiceNo, page, size);
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.INVOICE_ISSUE + "') and @perm.canBiz('"
            + BizPerms.INVOICE_READ + "') and @perm.canBiz('" + BizPerms.PRINT_EXPORT + "')")
    @PostMapping("/biz/invoices/{invoiceNo}/deliveries")
    public Delivery send(@PathVariable @Size(max = 64) String invoiceNo,
            @Valid @RequestBody SendReq request,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key) {
        return deliveries.enqueue(BizInvoiceScope.current(), invoiceNo, request.exportNo(),
                request.email(), request.reason(), key);
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.INVOICE_ISSUE + "') and @perm.canBiz('"
            + BizPerms.INVOICE_READ + "') and @perm.canBiz('" + BizPerms.PRINT_EXPORT + "')")
    @PostMapping("/biz/invoices/{invoiceNo}/deliveries/{deliveryNo}/resends")
    public Delivery resend(@PathVariable @Size(max = 64) String invoiceNo,
            @PathVariable @Size(max = 64) String deliveryNo, @Valid @RequestBody ResendReq request,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key) {
        return deliveries.resend(BizInvoiceScope.current(), invoiceNo, deliveryNo, request.reason(),
                request.acknowledgeUnknown(), key);
    }

    public record SendReq(@NotBlank @Size(max = 64) String exportNo,
            @NotBlank @Email @Size(max = 191) String email, @Size(max = 256) String reason) {
        @Override public String toString() { return "SendReq[exportNo=" + exportNo + "]"; }
    }

    public record ResendReq(@NotBlank @Size(max = 256) String reason, boolean acknowledgeUnknown) {}
}
