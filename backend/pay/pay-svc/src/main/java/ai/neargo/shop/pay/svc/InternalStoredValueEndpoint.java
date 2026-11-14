package ai.neargo.shop.pay.svc;

import ai.neargo.shop.pay.client.PayInternalPaths;
import ai.neargo.shop.pay.client.PayStoredValueRequests;
import ai.neargo.shop.pay.storedvalue.service.StoredValueRechargeService;
import ai.neargo.shop.pay.storedvalue.service.StoredValueCreditService;
import ai.neargo.shop.pay.storedvalue.service.StoredValueQueryService;
import ai.neargo.shop.pay.storedvalue.service.StoredValueRedemptionService;
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
    private final StoredValueQueryService queries;
    private final StoredValueRedemptionService redemptions;
    private final String token;
    public InternalStoredValueEndpoint(StoredValueRechargeService recharges, StoredValueCreditService credits,
            StoredValueRechargeFactsPort facts, StoredValueQueryService queries,
            StoredValueRedemptionService redemptions,
            @Value("${shop.services.internal-token:}") String token) {
        this.recharges = recharges; this.credits = credits; this.facts = facts; this.queries = queries;
        this.redemptions = redemptions; this.token = token;
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
    @PostMapping(PayInternalPaths.SV_QUERY)
    public ResponseEntity<?> query(@RequestBody PayStoredValueRequests.QueryRecharge r,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given,
                () -> recharges.query(r.context(), r.entityNo(), r.rechargeNo()));
    }
    @PostMapping(PayInternalPaths.SV_BALANCE)
    public ResponseEntity<?> balance(@RequestBody PayStoredValueRequests.Balance r,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> queries.balance(r.context(), r.query()));
    }
    @PostMapping(PayInternalPaths.SV_STORES)
    public ResponseEntity<?> stores(@RequestBody PayStoredValueRequests.Stores r,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> queries.stores(r.context(), r.query()));
    }
    @PostMapping(PayInternalPaths.SV_CONSUMPTIONS)
    public ResponseEntity<?> consumptions(@RequestBody PayStoredValueRequests.Consumptions r,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> queries.consumptions(r.context(), r.query()));
    }
    @PostMapping(PayInternalPaths.SV_USE_QUOTE)
    public ResponseEntity<?> useQuote(@RequestBody PayStoredValueRequests.UseQuote r,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> r.choice()==null?redemptions.quote(r.context(),r.sourceRef()):redemptions.quote(r.context(),r.sourceRef(),r.choice()));
    }
}
