package ai.neargo.shop.invoice.api.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.invoice.service.InvoiceDocumentService;
import ai.neargo.shop.print.api.dto.PrintExportDtos.ExportView;
import ai.neargo.shop.spi.invoice.InvoicePort.Scope;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Profile("api")
@ConditionalOnProperty(name = "shop.print.enabled", havingValue = "true")
@RestController
@Validated
public class BizInvoiceDocumentController {
    private final InvoiceDocumentService documents;

    public BizInvoiceDocumentController(InvoiceDocumentService documents) {
        this.documents = documents;
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.INVOICE_READ + "') and @perm.canBiz('" + BizPerms.PRINT_EXPORT + "')")
    @GetMapping("/biz/invoices/{invoiceNo}/documents")
    public PageData<ExportView> list(@PathVariable @NotBlank @Size(max = 64) String invoiceNo,
            @RequestParam(defaultValue = "1") @Min(1) @Max(10000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return documents.list(currentScope(), invoiceNo, page, size);
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.INVOICE_READ + "') and @perm.canBiz('" + BizPerms.PRINT_EXPORT + "')")
    @PostMapping("/biz/invoices/{invoiceNo}/documents")
    public ExportView create(@PathVariable @NotBlank @Size(max = 64) String invoiceNo,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key) {
        return documents.create(currentScope(), invoiceNo, key);
    }

    private static Scope currentScope() {
        var scope = BizInvoiceScope.current();
        BizContext.requireStoreAccess(scope.storeNo());
        return scope;
    }
}
