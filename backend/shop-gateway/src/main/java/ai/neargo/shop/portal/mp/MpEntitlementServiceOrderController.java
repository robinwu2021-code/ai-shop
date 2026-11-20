package ai.neargo.shop.portal.mp;

import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.paybridge.dto.EntitlementServiceViews;
import ai.neargo.shop.paybridge.dto.EntitlementServiceViews.ServiceView;
import ai.neargo.shop.promotion.service.ActivityCommandDeduplicator;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.trade.service.EntitlementServiceOrderService;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Consumer access to their own merchant-created service orders. */
@Profile("api")
@RestController
public class MpEntitlementServiceOrderController {
    private final EntitlementServiceOrderService serviceOrders;
    public MpEntitlementServiceOrderController(EntitlementServiceOrderService serviceOrders) {
        this.serviceOrders = serviceOrders;
    }
    @GetMapping("/mp/entitlement-service-orders")
    public List<ServiceView> pendingServices(@RequestParam(defaultValue = "20") int size) {
        var ctx = context("pending-services", "pending-services");
        return serviceOrders.pending(ctx.tenantNo(), ctx.actorNo(), size).stream()
                .map(EntitlementServiceViews::consumer).toList();
    }
    @GetMapping("/mp/entitlement-service-orders/{serviceOrderNo}")
    public ServiceView service(@PathVariable String serviceOrderNo) {
        var ctx = context("service-detail", serviceOrderNo);
        return EntitlementServiceViews.consumer(serviceOrders.mine(ctx.tenantNo(), ctx.actorNo(), serviceOrderNo));
    }
    private EntitlementContext context(String request, String payload) {
        var user = SecurityUtils.requireUser();
        if (user.realm() != Realm.CONSUMER) throw BizException.of(ErrorCode.FORBIDDEN);
        // Context IDs are bounded independently from the public idempotency key (up to 128 characters).
        return new EntitlementContext(user.tenantNo(), user.userNo(),
                ActivityCommandDeduplicator.hash(request), ActivityCommandDeduplicator.hash(payload));
    }
}
