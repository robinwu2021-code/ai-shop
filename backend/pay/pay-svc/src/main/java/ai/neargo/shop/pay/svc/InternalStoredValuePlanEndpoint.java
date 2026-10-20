package ai.neargo.shop.pay.svc;

import ai.neargo.shop.pay.client.PayInternalPaths;
import ai.neargo.shop.pay.client.PayStoredValuePlanRequests;
import ai.neargo.shop.pay.storedvalue.service.StoredValuePlanService;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.InternalServiceResponses;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InternalStoredValuePlanEndpoint {
    private final StoredValuePlanService plans;
    private final String token;
    public InternalStoredValuePlanEndpoint(StoredValuePlanService plans,
            @Value("${shop.services.internal-token:}") String token) {
        this.plans = plans; this.token = token;
    }
    @PostMapping(PayInternalPaths.SV_PLAN_CREATE)
    public ResponseEntity<?> create(@RequestBody PayStoredValuePlanRequests.Create r,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> plans.create(r.scope(), r.command(), r.key()));
    }
    @PostMapping(PayInternalPaths.SV_PLAN_GET)
    public ResponseEntity<?> get(@RequestBody PayStoredValuePlanRequests.Get r,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> plans.get(r.scope(), r.storeNo(), r.planNo()));
    }
    @PostMapping(PayInternalPaths.SV_PLAN_LIST)
    public ResponseEntity<?> list(@RequestBody PayStoredValuePlanRequests.List r,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> plans.list(r.scope(), r.storeNo(), r.status(), r.cursor(), r.size()));
    }
    @PostMapping(PayInternalPaths.SV_PLAN_STATUS)
    public ResponseEntity<?> status(@RequestBody PayStoredValuePlanRequests.Status r,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> plans.status(r.scope(), r.planNo(), r.command(), r.key()));
    }
}
