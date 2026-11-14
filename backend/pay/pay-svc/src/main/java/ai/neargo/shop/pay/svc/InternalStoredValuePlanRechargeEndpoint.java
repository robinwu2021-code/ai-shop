package ai.neargo.shop.pay.svc;

import ai.neargo.shop.pay.client.PayInternalPaths;
import ai.neargo.shop.pay.client.PayStoredValuePlanRechargeRequests;
import ai.neargo.shop.pay.storedvalue.service.StoredValuePlanRechargeService;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.InternalServiceResponses;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InternalStoredValuePlanRechargeEndpoint {
    private final StoredValuePlanRechargeService direct;
    private final String token;
    public InternalStoredValuePlanRechargeEndpoint(StoredValuePlanRechargeService direct,
            @Value("${shop.services.internal-token:}") String token) { this.direct=direct; this.token=token; }
    @PostMapping(PayInternalPaths.SV_CUSTOMER_BIND)
    public ResponseEntity<?> bind(@RequestBody PayStoredValuePlanRechargeRequests.Bind r,
            @RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String given){
        return InternalServiceResponses.invoke(token,given,()->{direct.bindCustomer(r.context(),r.customerNo(),r.userNo());return true;});
    }
    @PostMapping(PayInternalPaths.SV_PLAN_RECHARGE_CONFIRM_OFFLINE)
    public ResponseEntity<?> confirmOffline(@RequestBody PayStoredValuePlanRechargeRequests.Create r,
            @RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String given){
        return InternalServiceResponses.invoke(token,given,()->direct.confirmOffline(r.context(),r.entityNo(),r.command()));
    }
    @PostMapping(PayInternalPaths.SV_PLAN_RECHARGE_CREATE)
    public ResponseEntity<?> create(@RequestBody PayStoredValuePlanRechargeRequests.Create r,
            @RequestHeader(value=InternalHttp.TOKEN_HEADER, required=false) String given) {
        return InternalServiceResponses.invoke(token,given,()->direct.create(r.context(), r.entityNo(), r.command()));
    }
    @PostMapping(PayInternalPaths.SV_PLAN_RECHARGE_QUERY)
    public ResponseEntity<?> query(@RequestBody PayStoredValuePlanRechargeRequests.Query r,
            @RequestHeader(value=InternalHttp.TOKEN_HEADER, required=false) String given) {
        return InternalServiceResponses.invoke(token,given,()->direct.query(r.context(), r.entityNo(), r.rechargeNo()));
    }
    @PostMapping(PayInternalPaths.SV_PLAN_RECHARGE_RECOVER)
    public ResponseEntity<?> recover(@RequestBody PayStoredValuePlanRechargeRequests.Query r,
            @RequestHeader(value=InternalHttp.TOKEN_HEADER, required=false) String given) {
        return InternalServiceResponses.invoke(token,given,()->direct.recover(r.context(), r.entityNo(), r.rechargeNo()));
    }
    @PostMapping(PayInternalPaths.SV_PLAN_RECHARGE_REPLAY)
    public ResponseEntity<?> replay(@RequestBody PayStoredValuePlanRechargeRequests.Replay r,
            @RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String given){
        return InternalServiceResponses.invoke(token,given,()->direct.replay(r.context(),r.entityNo(),r.command()));
    }
    @PostMapping(PayInternalPaths.SV_PLAN_RECHARGE_CANCEL_CREATION)
    public ResponseEntity<?> cancelCreation(@RequestBody PayStoredValuePlanRechargeRequests.Replay r,
            @RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String given){
        return InternalServiceResponses.invoke(token,given,()->direct.cancelCreation(r.context(),r.entityNo(),r.command()));
    }
}
