package ai.neargo.shop.pay.svc;
import ai.neargo.shop.pay.client.PayInternalPaths;
import ai.neargo.shop.pay.storedvalue.settlement.StoredValueFinanceService;
import ai.neargo.shop.svc.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
@RestController
public class InternalStoredValueFinanceEndpoint {
 private final StoredValueFinanceService pay;private final String token;
 public InternalStoredValueFinanceEndpoint(StoredValueFinanceService pay,@Value("${shop.services.internal-token:}")String token){this.pay=pay;this.token=token;}
 @PostMapping(PayInternalPaths.SV_FINANCE_CAPABILITIES)
 public ResponseEntity<?> capabilities(@RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false)String given){return InternalServiceResponses.invoke(token,given,()->pay.capabilities());}
 @PostMapping(PayInternalPaths.SV_FINANCE_DIRECTORY)
 public ResponseEntity<?> directory(@RequestBody ai.neargo.shop.spi.storedvalue.StoredValueFinance.Directory c,@RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false)String given){return InternalServiceResponses.invoke(token,given,()->pay.directory(c));}
 @PostMapping(PayInternalPaths.SV_FINANCE_SUMMARY)
 public ResponseEntity<?> summary(@RequestBody ai.neargo.shop.spi.storedvalue.StoredValueFinance.SummaryRequest c,@RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false)String given){return InternalServiceResponses.invoke(token,given,()->pay.summary(c));}
 @PostMapping(PayInternalPaths.SV_FINANCE_STORES)
 public ResponseEntity<?> stores(@RequestBody ai.neargo.shop.spi.storedvalue.StoredValueReports.Query c,@RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false)String given){return InternalServiceResponses.invoke(token,given,()->pay.stores(c));}
 @PostMapping(PayInternalPaths.SV_FINANCE_ACTIVITIES)
 public ResponseEntity<?> activities(@RequestBody ai.neargo.shop.spi.storedvalue.StoredValueReports.Query c,@RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false)String given){return InternalServiceResponses.invoke(token,given,()->pay.activities(c));}
 @PostMapping(PayInternalPaths.SV_FINANCE_LOCAL_ISSUERS)
 public ResponseEntity<?> localIssuers(@RequestBody ai.neargo.shop.spi.storedvalue.StoredValueFinance.BatchReports c,@RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false)String given){return InternalServiceResponses.invoke(token,given,()->pay.localIssuers(c));}
 @PostMapping(PayInternalPaths.SV_FINANCE_LOCATE)
 public ResponseEntity<?> locate(@RequestBody ai.neargo.shop.spi.storedvalue.StoredValueSettlements.Resource c,@RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false)String given){return InternalServiceResponses.invoke(token,given,()->pay.locate(c));}
 @PostMapping(PayInternalPaths.SV_FINANCE_BILLS)
 public ResponseEntity<?> bills(@RequestBody ai.neargo.shop.spi.storedvalue.StoredValueSettlements.Query c,@RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false)String given){return InternalServiceResponses.invoke(token,given,()->pay.bills(c));}
 @PostMapping(PayInternalPaths.SV_FINANCE_DETAIL)
 public ResponseEntity<?> detail(@RequestBody ai.neargo.shop.spi.storedvalue.StoredValueFinance.BillRead c,@RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false)String given){return InternalServiceResponses.invoke(token,given,()->pay.detail(c));}
 @PostMapping(PayInternalPaths.SV_FINANCE_ITEMS)
 public ResponseEntity<?> items(@RequestBody ai.neargo.shop.spi.storedvalue.StoredValueFinance.BillRead c,@RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false)String given){return InternalServiceResponses.invoke(token,given,()->pay.items(c));}
 @PostMapping(PayInternalPaths.SV_FINANCE_EVENTS)
 public ResponseEntity<?> events(@RequestBody ai.neargo.shop.spi.storedvalue.StoredValueFinance.BillRead c,@RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false)String given){return InternalServiceResponses.invoke(token,given,()->pay.events(c));}
 @PostMapping(PayInternalPaths.SV_FINANCE_PAYMENTS)
 public ResponseEntity<?> payments(@RequestBody ai.neargo.shop.spi.storedvalue.StoredValueFinance.BillRead c,@RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false)String given){return InternalServiceResponses.invoke(token,given,()->pay.payments(c));}
 @PostMapping(PayInternalPaths.SV_FINANCE_GENERATE)
 public ResponseEntity<?> generate(@RequestBody ai.neargo.shop.spi.storedvalue.StoredValueSettlements.Generation c,@RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false)String given){return InternalServiceResponses.invoke(token,given,()->pay.generate(c));}
 @PostMapping(PayInternalPaths.SV_FINANCE_RECONCILE)
 public ResponseEntity<?> reconcile(@RequestBody ai.neargo.shop.spi.storedvalue.StoredValueSettlements.Decision c,@RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false)String given){return InternalServiceResponses.invoke(token,given,()->pay.reconcile(c));}
 @PostMapping(PayInternalPaths.SV_FINANCE_DISPUTE)
 public ResponseEntity<?> dispute(@RequestBody ai.neargo.shop.spi.storedvalue.StoredValueSettlements.Decision c,@RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false)String given){return InternalServiceResponses.invoke(token,given,()->pay.dispute(c));}
 @PostMapping(PayInternalPaths.SV_FINANCE_RESOLVE_DISPUTE)
 public ResponseEntity<?> resolveDispute(@RequestBody ai.neargo.shop.spi.storedvalue.StoredValueSettlements.Resolution c,@RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false)String given){return InternalServiceResponses.invoke(token,given,()->pay.resolveDispute(c));}
 @PostMapping(PayInternalPaths.SV_FINANCE_ADJUST)
 public ResponseEntity<?> adjust(@RequestBody ai.neargo.shop.spi.storedvalue.StoredValueSettlements.Adjustment c,@RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false)String given){return InternalServiceResponses.invoke(token,given,()->pay.adjust(c));}
 @PostMapping(PayInternalPaths.SV_FINANCE_REGISTER)
 public ResponseEntity<?> register(@RequestBody ai.neargo.shop.spi.storedvalue.StoredValueSettlements.Registration c,@RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false)String given){return InternalServiceResponses.invoke(token,given,()->pay.register(c));}
 @PostMapping(PayInternalPaths.SV_FINANCE_CONFIRM)
 public ResponseEntity<?> confirm(@RequestBody ai.neargo.shop.spi.storedvalue.StoredValueSettlements.Receipt c,@RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false)String given){return InternalServiceResponses.invoke(token,given,()->pay.confirm(c));}
 @PostMapping(PayInternalPaths.SV_FINANCE_VOID_PAYMENT)
 public ResponseEntity<?> voidPayment(@RequestBody ai.neargo.shop.spi.storedvalue.StoredValueSettlements.VoidPayment c,@RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false)String given){return InternalServiceResponses.invoke(token,given,()->pay.voidPayment(c));}
 @PostMapping(PayInternalPaths.SV_FINANCE_RESOLVE_PAYMENT)
 public ResponseEntity<?> resolvePayment(@RequestBody ai.neargo.shop.spi.storedvalue.StoredValueFinance.ResolvePayment c,@RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false)String given){return InternalServiceResponses.invoke(token,given,()->pay.resolvePayment(c));}
 @PostMapping(PayInternalPaths.SV_FINANCE_MEMBERSHIP_TARGETS)
 public ResponseEntity<?> membershipTargets(@RequestBody ai.neargo.shop.spi.storedvalue.StoredValueMembership.Describe c,@RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false)String given){return InternalServiceResponses.invoke(token,given,()->pay.membershipTargets(c));}
 @PostMapping(PayInternalPaths.SV_FINANCE_PREPARE_MEMBERSHIP)
 public ResponseEntity<?> prepareMembership(@RequestBody ai.neargo.shop.spi.storedvalue.StoredValueMembership.Prepare c,@RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false)String given){return InternalServiceResponses.invoke(token,given,()->pay.prepareMembership(c));}
 @PostMapping(PayInternalPaths.SV_FINANCE_COMMIT_MEMBERSHIP)
 public ResponseEntity<?> commitMembership(@RequestBody ai.neargo.shop.spi.storedvalue.StoredValueMembership.Commit c,@RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false)String given){return InternalServiceResponses.invoke(token,given,()->pay.commitMembership(c));}
 @PostMapping(PayInternalPaths.SV_FINANCE_COLLECTION_REQUIREMENT)
 public ResponseEntity<?> collectionRequirement(@RequestBody ai.neargo.shop.spi.storedvalue.StoredValueWallets.ExecutionScope c,@RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false)String given){return InternalServiceResponses.invoke(token,given,()->pay.collectionRequirement(c));}
 @PostMapping(PayInternalPaths.SV_FINANCE_COLLECTION_OPTIONS)
 public ResponseEntity<?> collectionOptions(@RequestBody ai.neargo.shop.spi.storedvalue.StoredValueCollections.Options c,@RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false)String given){return InternalServiceResponses.invoke(token,given,()->pay.collectionOptions(c));}
 @PostMapping(PayInternalPaths.SV_FINANCE_REBUILD)
 public ResponseEntity<?> rebuild(@RequestBody ai.neargo.shop.spi.storedvalue.StoredValueReports.Projection c,@RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false)String given){return InternalServiceResponses.invoke(token,given,()->pay.rebuild(c));}
}
