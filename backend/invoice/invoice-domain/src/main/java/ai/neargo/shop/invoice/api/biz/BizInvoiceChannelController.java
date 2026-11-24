package ai.neargo.shop.invoice.api.biz;

import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.invoice.dto.InvoiceChannelDtos.AccountView;
import ai.neargo.shop.invoice.dto.InvoiceChannelDtos.ApplicationDetail;
import ai.neargo.shop.invoice.dto.InvoiceChannelDtos.ApplicationView;
import ai.neargo.shop.invoice.dto.InvoiceChannelDtos.ReviseCommand;
import ai.neargo.shop.invoice.dto.InvoiceChannelDtos.RouteView;
import ai.neargo.shop.invoice.dto.InvoiceChannelDtos.SubmitCommand;
import ai.neargo.shop.invoice.service.InvoiceChannelService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@Profile("api")
@Validated
@RestController
public class BizInvoiceChannelController {
    private final InvoiceChannelService channels;

    public BizInvoiceChannelController(InvoiceChannelService channels) {
        this.channels = channels;
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.INVOICE_SETUP + "')")
    @PostMapping("/biz/invoice-channel-applications")
    public ApplicationView submit(@RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            @Valid @RequestBody SubmitCommand command) {
        return channels.submit(BizInvoiceScope.current(), command, key);
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.INVOICE_SETUP + "')")
    @GetMapping("/biz/invoice-channel-applications")
    public List<ApplicationView> applications() {
        return channels.applications(BizInvoiceScope.current());
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.INVOICE_SETUP + "')")
    @GetMapping("/biz/invoice-channel-applications/{applicationNo}")
    public ApplicationDetail application(@PathVariable @Size(max = 64) String applicationNo) {
        return channels.application(BizInvoiceScope.current(), applicationNo);
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.INVOICE_SETUP + "')")
    @PostMapping("/biz/invoice-channel-applications/{applicationNo}/revisions")
    public ApplicationView revise(@PathVariable @Size(max = 64) String applicationNo,
            @Valid @RequestBody ReviseCommand command) {
        return channels.revise(BizInvoiceScope.current(), applicationNo, command);
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.INVOICE_SETUP + "')")
    @GetMapping("/biz/invoice-channel-accounts")
    public List<AccountView> accounts() {
        return channels.accounts(BizInvoiceScope.current());
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.INVOICE_SETUP + "')")
    @GetMapping("/biz/invoice-channel-routes")
    public List<RouteView> routes() {
        return channels.routes(BizInvoiceScope.current());
    }
}
