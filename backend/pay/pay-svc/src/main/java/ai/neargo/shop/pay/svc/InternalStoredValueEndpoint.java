package ai.neargo.shop.pay.svc;

import ai.neargo.shop.pay.client.PayInternalPaths;
import ai.neargo.shop.pay.client.PayStoredValueRequests;
import ai.neargo.shop.pay.storedvalue.service.StoredValueRechargeService;
import ai.neargo.shop.pay.storedvalue.service.StoredValueCreditService;
import ai.neargo.shop.spi.storedvalue.StoredValueRechargeFactsPort;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.InternalServiceResponses;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InternalStoredValueEndpoint {
    private final StoredValueRechargeService recharges;
    private final StoredValueCreditService credits;
    private final StoredValueRechargeFactsPort facts;
    private final String token;
    public InternalStoredValueEndpoint(StoredValueRechargeService recharges, StoredValueCreditService credits,
            StoredValueRechargeFactsPort facts, @Value("${shop.services.internal-token:}") String token) {
        this.recharges = recharges; this.credits = credits; this.facts = facts; this.token = token;
    }
    @PostMapping(PayInternalPaths.SV_FACTS)
    public ResponseEntity<?> facts(@RequestBody PayStoredValueRequests.Query r,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given,
                () -> facts.query(r.context(), r.entityNo(), r.rechargeNo()));
    }
    @PostMapping(PayInternalPaths.SV_CREATE)
    public ResponseEntity<?> create(@RequestBody PayStoredValueRequests.Create r,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given,
                () -> recharges.create(r.context(), r.entityNo(), r.command()));
    }
    @PostMapping(PayInternalPaths.SV_SUBMIT)
    public ResponseEntity<?> submit(@RequestBody PayStoredValueRequests.Submit r,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given,
                () -> recharges.submit(r.context(), r.entityNo(), r.command()));
    }
    @PostMapping(PayInternalPaths.SV_RECEIVE)
    public ResponseEntity<?> receive(@RequestBody PayStoredValueRequests.Receive r,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given,
                () -> recharges.receive(r.context(), r.entityNo(), r.command()));
    }
    @PostMapping(PayInternalPaths.SV_CANCEL)
    public ResponseEntity<?> cancel(@RequestBody PayStoredValueRequests.Cancel r,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given,
                () -> recharges.cancel(r.context(), r.entityNo(), r.command()));
    }
    @PostMapping(PayInternalPaths.SV_CREDIT)
    public ResponseEntity<?> credit(@RequestBody PayStoredValueRequests.Credit r,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given,
                () -> credits.credit(r.context(), r.command()));
    }
}
