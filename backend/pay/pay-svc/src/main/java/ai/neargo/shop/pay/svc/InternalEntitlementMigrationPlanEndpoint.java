package ai.neargo.shop.pay.svc;

import ai.neargo.shop.pay.client.PayInternalPaths;
import ai.neargo.shop.pay.client.PayMigrationRequests;
import ai.neargo.shop.pay.migration.service.EntitlementMigrationPlanService;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.InternalServiceResponses;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Token-protected plan tooling, metadata only; no source commit or financial import endpoint. */
@RestController
public class InternalEntitlementMigrationPlanEndpoint {
    private final EntitlementMigrationPlanService plans;
    private final String token;
    public InternalEntitlementMigrationPlanEndpoint(EntitlementMigrationPlanService plans,@Value("${shop.services.internal-token:}") String token) {
        this.plans=plans;this.token=token;
    }
    @PostMapping(PayInternalPaths.MIGRATION_PLAN)
    public ResponseEntity<?> plan(@RequestBody PayMigrationRequests.Plan request,
            @RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String supplied) {
        return InternalServiceResponses.invoke(token,supplied,()->plans.prepare(request.scope(),request.command().stageNo(),
                request.command().reason(),request.requestKey()));
    }
    @PostMapping(PayInternalPaths.MIGRATION_PLAN_PREPARED)
    public ResponseEntity<?> prepared(@RequestBody PayMigrationRequests.PlanGet request,
            @RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String supplied) {
        return InternalServiceResponses.invoke(token,supplied,()->plans.prepared(request.scope(),request.planNo()));
    }
    @PostMapping(PayInternalPaths.MIGRATION_PLAN_GET)
    public ResponseEntity<?> get(@RequestBody PayMigrationRequests.PlanGet request,
            @RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String supplied) {
        return InternalServiceResponses.invoke(token,supplied,()->plans.get(request.scope(),request.planNo()));
    }
    @PostMapping(PayInternalPaths.MIGRATION_PLAN_VERIFY)
    public ResponseEntity<?> verify(@RequestBody PayMigrationRequests.PlanGet request,
            @RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String supplied) {
        return InternalServiceResponses.invoke(token,supplied,()->plans.verify(request.scope(),request.planNo()));
    }
}
