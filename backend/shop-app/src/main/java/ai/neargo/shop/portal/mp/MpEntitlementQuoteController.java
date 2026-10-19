package ai.neargo.shop.portal.mp;

import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.paybridge.EntitlementCheckoutCoordinator;
import ai.neargo.shop.spi.entitlement.ContractChecks;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.QuoteView;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Profile("api")
@RestController
public class MpEntitlementQuoteController {
    private final EntitlementCheckoutCoordinator checkout;
    public MpEntitlementQuoteController(EntitlementCheckoutCoordinator checkout) { this.checkout = checkout; }
    @PostMapping("/mp/entitlement-quotes")
    public QuoteView quote(@RequestBody QuoteRequest q) {
        var user = SecurityUtils.requireUser();
        if (user.realm() != Realm.CONSUMER) throw BizException.of(ErrorCode.FORBIDDEN);
        var ctx = new EntitlementContext(user.tenantNo(), user.userNo(), "checkout-quote", "0".repeat(64));
        return checkout.quote(ctx, q.orderDraftRef(), q.orderRevision(), q.selectedInstanceNos());
    }
    public record QuoteRequest(String orderDraftRef, long orderRevision, List<String> selectedInstanceNos) {
        public QuoteRequest {
            ContractChecks.id(orderDraftRef); ContractChecks.require(orderRevision >= 0);
            selectedInstanceNos = List.copyOf(ContractChecks.present(selectedInstanceNos));
            selectedInstanceNos.forEach(ContractChecks::id);
            ContractChecks.require(selectedInstanceNos.size() <= 20);
            ContractChecks.require(selectedInstanceNos.stream().distinct().count() == selectedInstanceNos.size());
        }
    }
}
