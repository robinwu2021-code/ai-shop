package ai.neargo.shop.invoice.api.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.invoice.dto.InvoiceChannelDtos.AccountView;
import ai.neargo.shop.invoice.dto.InvoiceChannelDtos.ActivateCommand;
import ai.neargo.shop.invoice.dto.InvoiceChannelDtos.ApplicationDetail;
import ai.neargo.shop.invoice.dto.InvoiceChannelDtos.ApplicationView;
import ai.neargo.shop.invoice.dto.InvoiceChannelDtos.BindCommand;
import ai.neargo.shop.invoice.dto.InvoiceChannelDtos.ReviewCommand;
import ai.neargo.shop.invoice.dto.InvoiceChannelDtos.RouteView;
import ai.neargo.shop.invoice.service.InvoiceChannelService;
import ai.neargo.shop.spi.invoice.InvoicePort.Scope;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Profile("ops")
@Validated
@RestController
public class OpsInvoiceChannelController {
    private final InvoiceChannelService channels;

    public OpsInvoiceChannelController(InvoiceChannelService channels) {
        this.channels = channels;
    }

    @PreAuthorize("@perm.can('" + Perms.FINANCE_INVOICE_READ + "')")
    @GetMapping("/ops/invoice-channel-applications")
    public List<ApplicationView> applications(@RequestParam @Size(max = 64) String entityNo) {
        return channels.applications(scope(entityNo));
    }

    @PreAuthorize("@perm.can('" + Perms.FINANCE_INVOICE_READ + "')")
    @GetMapping("/ops/invoice-channel-applications/{applicationNo}")
    public ApplicationDetail application(@PathVariable @Size(max = 64) String applicationNo,
            @RequestParam @Size(max = 64) String entityNo) {
        return channels.application(scope(entityNo), applicationNo);
    }

    @PreAuthorize("@perm.can('" + Perms.FINANCE_INVOICE_VERIFY + "')")
    @PostMapping("/ops/invoice-channel-applications/{applicationNo}/reviews")
    public ApplicationView review(@PathVariable @Size(max = 64) String applicationNo,
            @Valid @RequestBody ReviewCommand command) {
        return channels.review(scope(command.entityNo()), applicationNo, command);
    }

    @PreAuthorize("@perm.can('" + Perms.FINANCE_INVOICE_VERIFY + "')")
    @PostMapping("/ops/invoice-channel-applications/{applicationNo}/account-bindings")
    public AccountView bind(@PathVariable @Size(max = 64) String applicationNo, @Valid @RequestBody BindCommand command) {
        return channels.verifyAndBind(scope(command.entityNo()), applicationNo, command.expectedRevision(),
                command.credentials());
    }

    @PreAuthorize("@perm.can('" + Perms.FINANCE_INVOICE_VERIFY + "')")
    @PostMapping("/ops/invoice-channel-routes/activations")
    public RouteView activate(@Valid @RequestBody ActivateCommand command) {
        return channels.activate(scope(command.entityNo()), command);
    }

    private static Scope scope(String entityNo) {
        if (!OpsEntityScope.allows(OpsEntityScope.current(), entityNo)) {
            throw BizException.of(ErrorCode.INVOICE_NOT_FOUND);
        }
        var user = SecurityUtils.requireUser();
        return new Scope(user.tenantNo(), entityNo, "*", user.userNo());
    }
}
