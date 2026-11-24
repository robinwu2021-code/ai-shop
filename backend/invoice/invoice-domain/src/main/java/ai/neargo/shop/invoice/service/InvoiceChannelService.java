package ai.neargo.shop.invoice.service;

import ai.neargo.shop.invoice.dto.InvoiceChannelDtos.AccountView;
import ai.neargo.shop.invoice.dto.InvoiceChannelDtos.ActivateCommand;
import ai.neargo.shop.invoice.dto.InvoiceChannelDtos.ApplicationDetail;
import ai.neargo.shop.invoice.dto.InvoiceChannelDtos.ApplicationView;
import ai.neargo.shop.invoice.dto.InvoiceChannelDtos.ChannelCredentials;
import ai.neargo.shop.invoice.dto.InvoiceChannelDtos.ReviewCommand;
import ai.neargo.shop.invoice.dto.InvoiceChannelDtos.ReviseCommand;
import ai.neargo.shop.invoice.dto.InvoiceChannelDtos.RouteView;
import ai.neargo.shop.invoice.dto.InvoiceChannelDtos.SubmitCommand;
import ai.neargo.shop.invoice.entity.IvcRegimeProfile;
import ai.neargo.shop.spi.invoice.InvoicePort.Scope;
import java.util.List;
import java.util.Optional;

public interface InvoiceChannelService {
    ApplicationView submit(Scope scope, SubmitCommand command, String idempotencyKey);
    ApplicationView revise(Scope scope, String applicationNo, ReviseCommand command);
    ApplicationView review(Scope scope, String applicationNo, ReviewCommand command);
    AccountView verifyAndBind(Scope scope, String applicationNo, int expectedRevision);
    AccountView verifyAndBind(Scope scope, String applicationNo, int expectedRevision, ChannelCredentials credentials);
    RouteView activate(Scope scope, ActivateCommand command);
    ApplicationDetail application(Scope scope, String applicationNo);
    List<ApplicationView> applications(Scope scope);
    List<AccountView> accounts(Scope scope);
    List<RouteView> routes(Scope scope);

    /** Resolve under the current issuance transaction and keep its route locked until commit. */
    Optional<BoundAccount> activeForIssue(Scope scope, String regimeCode);

    /** Load the original account identity using its latest verified credential revision. */
    Optional<BoundAccount> historical(String tenantNo, String entityNo, String accountNo);

    record BoundAccount(
            String accountNo, String channelCode, String environment, long accountRevision, long routeVersion,
            IvcRegimeProfile profile) {}
}
