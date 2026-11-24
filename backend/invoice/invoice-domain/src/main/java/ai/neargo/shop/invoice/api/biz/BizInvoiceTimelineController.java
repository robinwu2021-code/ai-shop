package ai.neargo.shop.invoice.api.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.invoice.service.InvoiceLifecycleTimeline;
import ai.neargo.shop.invoice.service.InvoiceLifecycleTimeline.Event;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Profile("api")
@RestController
@Validated
public class BizInvoiceTimelineController {
    private final InvoiceLifecycleTimeline timeline;

    public BizInvoiceTimelineController(InvoiceLifecycleTimeline timeline) { this.timeline = timeline; }

    @PreAuthorize("@perm.canBiz('" + BizPerms.INVOICE_READ + "')")
    @GetMapping("/biz/invoices/{invoiceNo}/timeline")
    public PageData<Event> timeline(@PathVariable @Size(max = 64) String invoiceNo,
            @RequestParam(defaultValue = "1") @Min(1) @Max(10000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        var context = BizContext.current();
        return timeline.list(BizInvoiceScope.current(), invoiceNo, context.can(BizPerms.PRINT_READ),
                context.can(BizPerms.PRINT_EXPORT), page, size);
    }
}
