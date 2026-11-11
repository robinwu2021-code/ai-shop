package ai.neargo.shop.beauty.server.api;

import ai.neargo.shop.auth.BoundDelegatedCalls;
import ai.neargo.shop.beauty.config.ConditionalOnBeautyEnabled;
import ai.neargo.shop.beauty.service.impl.WorkOrderEntitlementService;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.ServiceWorkOrderUseInternalApi;
import ai.neargo.shop.spi.entitlement.ServiceWorkOrderUsePort.Lookup;
import ai.neargo.shop.spi.security.InternalCall;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.InternalServiceResponses;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnBeautyEnabled
public class InternalWorkOrderEntitlementController {
    private final WorkOrderEntitlementService owner;
    private final BoundDelegatedCalls human;
    private final String token;
    public InternalWorkOrderEntitlementController(WorkOrderEntitlementService owner,
            @Value("${shop.services.internal-token:}") String token) {
        this.owner = owner; this.token = token; this.human = new BoundDelegatedCalls(token);
    }

    @PostMapping(ServiceWorkOrderUseInternalApi.RESERVE)
    public ResponseEntity<?> reserve(@RequestBody InternalCall<Lookup> call,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return human.invoke(given, call, ServiceWorkOrderUseInternalApi.ENDPOINT, "RESERVE",
                BoundDelegatedCalls.Mode.MERCHANT, call.request().operationNo(), () -> {
                    var lookup = call.request();
                    if (!lookup.tenantNo().equals(call.binding().identity().tenantNo())
                            || !lookup.entityNo().equals(call.binding().entityNo())
                            || !lookup.storeNo().equals(call.binding().storeNo())) {
                        throw ai.neargo.shop.common.BizException.of(ai.neargo.shop.common.ErrorCode.FORBIDDEN);
                    }
                    var context = new EntitlementContext(lookup.tenantNo(), call.binding().identity().actorNo(),
                            lookup.operationNo(), "0".repeat(64));
                    return owner.reserve(context, lookup);
                });
    }

    @PostMapping(ServiceWorkOrderUseInternalApi.INSPECT)
    public ResponseEntity<?> inspect(@RequestBody Lookup lookup,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> owner.inspect(lookup));
    }

    @PostMapping(ServiceWorkOrderUseInternalApi.RECONCILE)
    public ResponseEntity<?> reconcile(@RequestBody Lookup lookup,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> owner.reconcile(lookup));
    }
}
