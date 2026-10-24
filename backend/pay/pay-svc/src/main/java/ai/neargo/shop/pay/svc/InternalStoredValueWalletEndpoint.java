package ai.neargo.shop.pay.svc;
import ai.neargo.shop.pay.client.*;
import ai.neargo.shop.pay.storedvalue.issuer.StoredValueIssuerQueryService;
import ai.neargo.shop.spi.storedvalue.StoredValueWallets.Query;
import ai.neargo.shop.svc.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
@RestController
public class InternalStoredValueWalletEndpoint {
    @org.springframework.beans.factory.annotation.Autowired private ai.neargo.shop.pay.storedvalue.service.impl.StoredValueMerchantQueryServiceImpl records;
    private final StoredValueIssuerQueryService service;private final String token;
    public InternalStoredValueWalletEndpoint(StoredValueIssuerQueryService service,@Value("${shop.services.internal-token:}")String token){this.service=service;this.token=token;}
    @PostMapping(PayInternalPaths.SV_WALLETS_ACCOUNTS)
    public ResponseEntity<?> accounts(@RequestBody Query request,@RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false)String given){return InternalServiceResponses.invoke(token,given,()->service.accounts(request));}
    @PostMapping(PayInternalPaths.SV_WALLETS_SELECT)
    public ResponseEntity<?> select(@RequestBody PayStoredValueWalletRequests.Select request,@RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false)String given){return InternalServiceResponses.invoke(token,given,()->service.select(request.query(),request.issuerRef(),request.accountRef()));}
    @PostMapping(PayInternalPaths.SV_WALLETS_RECORDS)
    public ResponseEntity<?> records(@RequestBody PayStoredValueWalletRequests.Records r,@RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false)String given){
        return InternalServiceResponses.invoke(token,given,()->{
            var q=r.selection().query();var s=q.scope();var selected=service.readSelection(q,r.selection().issuerRef(),r.selection().accountRef());
            return records.walletRecords(new ai.neargo.shop.pay.entitlement.dto.CatalogScope(s.tenantNo(),s.entityNo(),s.actorNo(),false),s.storeNo(),r.type(),r.from(),r.to(),selected.accountNo(),r.cursor(),r.size());
        });
    }
}
