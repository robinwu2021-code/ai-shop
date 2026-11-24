package ai.neargo.shop.invoice.api.biz;

import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.invoice.dto.InvoiceApplicationDtos.ApplicationView;
import ai.neargo.shop.invoice.dto.InvoiceApplicationDtos.ChangeApplicationStateReq;
import ai.neargo.shop.invoice.dto.InvoiceApplicationDtos.EventView;
import ai.neargo.shop.invoice.dto.InvoiceApplicationDtos.IssueApplicationReq;
import ai.neargo.shop.invoice.dto.InvoiceApplicationDtos.ReviseApplicationReq;
import ai.neargo.shop.invoice.dto.InvoiceApplicationDtos.RevisionView;
import ai.neargo.shop.invoice.dto.InvoiceApplicationDtos.SubmitApplicationReq;
import ai.neargo.shop.invoice.dto.InvoiceDtos.InvoiceVO;
import ai.neargo.shop.invoice.service.InvoiceApplicationService;
import ai.neargo.shop.invoice.service.InvoiceService;
import ai.neargo.shop.invoice.service.InvoiceTransmissionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Profile("api")
@RestController
@Validated
public class BizInvoiceApplicationController {

    private static final Logger log = LoggerFactory.getLogger(BizInvoiceApplicationController.class);
    private final InvoiceApplicationService applications;
    private final InvoiceService invoices;
    private final InvoiceTransmissionService transmissions;
    private final boolean syncUpload;

    public BizInvoiceApplicationController(InvoiceApplicationService applications, InvoiceService invoices,
            InvoiceTransmissionService transmissions, @Value("${shop.invoice.transmission.sync:false}") boolean syncUpload) {
        this.applications = applications;
        this.invoices = invoices;
        this.transmissions = transmissions;
        this.syncUpload = syncUpload;
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.INVOICE_ISSUE + "')")
    @PostMapping("/biz/invoice-applications")
    public ApplicationView submit(@Valid @RequestBody SubmitApplicationReq request,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key) {
        return applications.submit(BizInvoiceScope.current(), request, key);
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.INVOICE_READ + "')")
    @GetMapping("/biz/invoice-applications")
    public PageData<ApplicationView> list(
            @RequestParam(required = false) @Size(max = 32) String sourceType,
            @RequestParam(required = false) @Size(max = 64) String sourceNo,
            @RequestParam(required = false) @Pattern(regexp = "SUBMITTED|REJECTED|CANCELLED|ISSUED") String status,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return applications.list(BizInvoiceScope.current(), sourceType, sourceNo, status, page, size);
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.INVOICE_READ + "')")
    @GetMapping("/biz/invoice-applications/{applicationNo}")
    public ApplicationView get(@PathVariable @Size(max = 64) String applicationNo) {
        return applications.get(BizInvoiceScope.current(), applicationNo);
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.INVOICE_READ + "')")
    @GetMapping("/biz/invoice-applications/{applicationNo}/revisions")
    public List<RevisionView> revisions(@PathVariable @Size(max = 64) String applicationNo) {
        return applications.revisions(BizInvoiceScope.current(), applicationNo);
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.INVOICE_READ + "')")
    @GetMapping("/biz/invoice-applications/{applicationNo}/events")
    public List<EventView> events(@PathVariable @Size(max = 64) String applicationNo) {
        return applications.events(BizInvoiceScope.current(), applicationNo);
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.INVOICE_ISSUE + "')")
    @PostMapping("/biz/invoice-applications/{applicationNo}/revisions")
    public ApplicationView revise(@PathVariable @Size(max = 64) String applicationNo,
            @Valid @RequestBody ReviseApplicationReq request,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key) {
        return applications.revise(BizInvoiceScope.current(), applicationNo, request, key);
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.INVOICE_SETUP + "')")
    @PostMapping("/biz/invoice-applications/{applicationNo}/rejections")
    public ApplicationView reject(@PathVariable @Size(max = 64) String applicationNo,
            @Valid @RequestBody ChangeApplicationStateReq request,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key) {
        return applications.reject(BizInvoiceScope.current(), applicationNo, request, key);
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.INVOICE_SETUP + "')")
    @PostMapping("/biz/invoice-applications/{applicationNo}/cancellations")
    public ApplicationView cancel(@PathVariable @Size(max = 64) String applicationNo,
            @Valid @RequestBody ChangeApplicationStateReq request,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key) {
        return applications.cancel(BizInvoiceScope.current(), applicationNo, request, key);
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.INVOICE_ISSUE + "')")
    @PostMapping("/biz/invoice-applications/{applicationNo}/issuances")
    public InvoiceVO issue(@PathVariable @Size(max = 64) String applicationNo,
            @Valid @RequestBody IssueApplicationReq request,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key) {
        var scope = BizInvoiceScope.current();
        var receipt = applications.issue(scope, applicationNo, request, key);
        if (syncUpload) {
            try {
                transmissions.transmitNow(scope, receipt.invoiceNo());
            } catch (RuntimeException e) {
                log.warn("INVOICE_APPLICATION_UPLOAD_FAILED invoiceNo={}", receipt.invoiceNo(), e);
            }
        }
        return invoices.get(scope, receipt.invoiceNo());
    }
}
