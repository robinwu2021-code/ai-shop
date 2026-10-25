package ai.neargo.shop.portal.biz;
import ai.neargo.shop.common.*;
import ai.neargo.shop.merchantbridge.StoredValueFinanceContext;
import ai.neargo.shop.payclient.StoredValueFinanceAppService;
import ai.neargo.shop.spi.storedvalue.*;
import ai.neargo.shop.spi.storedvalue.StoredValueSettlements.*;
import java.time.*;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import ai.neargo.shop.spi.entitlement.ContractChecks;
@Profile("api") @RestController @PreAuthorize("@managementAccess.authenticated()")
public class BizStoredValueSettlementController {
 private final StoredValueFinanceContext context;private final StoredValueFinanceAppService finance;
 public BizStoredValueSettlementController(StoredValueFinanceContext context,StoredValueFinanceAppService finance){this.context=context;this.finance=finance;}
 public record GenerationRequest(String managementNo,String issuerNo,LocalDate periodFrom,LocalDate periodTo,String currency){}
 public record DecisionRequest(String managementNo,long expectedRevision,String side,String reason){}
 public record ResolutionRequest(String managementNo,long expectedRevision,String disputeNo,String decision,String reason){}
 public record AdjustmentRequest(String managementNo,String clearingNo,long expectedRevision,long payableDeltaMinor,String reason){}
 public record PaymentRequest(String managementNo,long expectedRevision,long amountMinor,Instant paidAt,String method,String receiptReference,List<String> evidenceRefs){
  @Override public String toString(){return "PaymentRequest[receiptReference=REDACTED,evidenceRefs=REDACTED]";}
 }
 public record ReceiptRequest(String managementNo,long expectedRevision,Instant receivedAt){}
 public record VoidRequest(String managementNo,long expectedRevision,String reason){}
 public record PermissionRequest(String managementNo){public PermissionRequest{ContractChecks.id(managementNo);}}
 @PostMapping("/biz/management/entities/{entityNo}/stored-value/financial-permissions")
 public Map<String,Object> preparePermissions(@PathVariable String entityNo,@RequestBody PermissionRequest c){return context.prepare(c.managementNo(),entityNo);}
 public record BillAccess(Set<String> payerActions,Set<String> payeeActions){}
 @GetMapping("/biz/management/stored-value/financial-capability")
 public StoredValueFinance.Capabilities capability(){return finance.capabilities();}
 @GetMapping("/biz/management/stored-value/settlement-bills/{billNo}/actions")
 public BillAccess actions(@PathVariable String billNo,@RequestParam String managementNo){
  var authority=context.bill(managementNo,billNo,"BILL");var bill=finance.detail(new StoredValueFinance.BillRead(authority,billNo,null,20));
  return new BillAccess(authority.entityPermissions().getOrDefault(bill.payerEntityNo(),Set.of()),authority.entityPermissions().getOrDefault(bill.payeeEntityNo(),Set.of()));
 }
 @GetMapping("/biz/management/stored-value/financial-resources")
 public List<StoredValueFinance.Resource> resources(@RequestParam String managementNo,@RequestParam(required=false)String scopeType){return context.directory(managementNo,scopeType);}
 @GetMapping("/biz/management/stored-value/settlement-bills")
 public CursorPage<Bill> bills(@RequestParam String managementNo,@RequestParam(required=false)String scopeType,@RequestParam(required=false)String issuerNo,@RequestParam(required=false)String status,@RequestParam(required=false)String currency,@RequestParam(required=false)Instant periodFrom,@RequestParam(required=false)Instant periodTo,@RequestParam(required=false)String cursor,@RequestParam(defaultValue="20")int size){if((periodFrom==null)!=(periodTo==null))throw BizException.of(ErrorCode.BAD_REQUEST);if(scopeType!=null&&!Set.of("STORE","BRAND").contains(scopeType))throw BizException.of(ErrorCode.BAD_REQUEST);return finance.bills(new Query(context.allFinancial(managementNo,scopeType,issuerNo),status,periodFrom==null?null:new StoredValueReports.Period(periodFrom,periodTo),cursor,size,currency));}
 @PostMapping("/biz/management/stored-value/settlement-bills/generations")
 public Generated generate(@RequestBody GenerationRequest r,@RequestHeader("Idempotency-Key")String key){return finance.generate(new Generation(context.financial(r.managementNo(),r.issuerNo()),r.issuerNo(),context.financialPeriod(r.managementNo(),r.issuerNo(),r.periodFrom(),r.periodTo()),r.currency(),key));}
 @GetMapping("/biz/management/stored-value/settlement-bills/{billNo}")
 public Bill detail(@PathVariable String billNo,@RequestParam String managementNo){return finance.detail(new StoredValueFinance.BillRead(context.bill(managementNo,billNo,"BILL"),billNo,null,20));}
 @GetMapping("/biz/management/stored-value/settlement-bills/{billNo}/items")
 public CursorPage<Item> items(@PathVariable String billNo,@RequestParam String managementNo,@RequestParam(required=false)String cursor,@RequestParam(defaultValue="20")int size){return finance.items(new StoredValueFinance.BillRead(context.bill(managementNo,billNo,"BILL"),billNo,cursor,size));}
 @GetMapping("/biz/management/stored-value/settlement-bills/{billNo}/events")
 public CursorPage<Event> events(@PathVariable String billNo,@RequestParam String managementNo,@RequestParam(required=false)String cursor,@RequestParam(defaultValue="20")int size){return finance.events(new StoredValueFinance.BillRead(context.bill(managementNo,billNo,"BILL"),billNo,cursor,size));}
 @GetMapping("/biz/management/stored-value/settlement-bills/{billNo}/payments")
 public CursorPage<Payment> payments(@PathVariable String billNo,@RequestParam String managementNo,@RequestParam(required=false)String cursor,@RequestParam(defaultValue="20")int size){return finance.payments(new StoredValueFinance.BillRead(context.bill(managementNo,billNo,"BILL"),billNo,cursor,size));}
 @PostMapping("/biz/management/stored-value/settlement-bills/{billNo}/reconciliation-confirmations")
 public Bill reconcile(@PathVariable String billNo,@RequestBody DecisionRequest r,@RequestHeader("Idempotency-Key")String key){return finance.reconcile(new Decision(context.bill(r.managementNo(),billNo,"BILL"),billNo,r.expectedRevision(),r.side(),r.reason(),key));}
 @PostMapping("/biz/management/stored-value/settlement-bills/{billNo}/disputes")
 public Bill dispute(@PathVariable String billNo,@RequestBody DecisionRequest r,@RequestHeader("Idempotency-Key")String key){return finance.dispute(new Decision(context.bill(r.managementNo(),billNo,"BILL"),billNo,r.expectedRevision(),r.side(),r.reason(),key));}
 @PostMapping("/biz/management/stored-value/settlement-bills/{billNo}/dispute-resolutions")
 public Bill resolveDispute(@PathVariable String billNo,@RequestBody ResolutionRequest r,@RequestHeader("Idempotency-Key")String key){return finance.resolveDispute(new Resolution(context.bill(r.managementNo(),billNo,"BILL"),billNo,r.expectedRevision(),r.disputeNo(),r.decision(),r.reason(),key));}
 @PostMapping("/biz/management/stored-value/settlement-bills/{billNo}/adjustments")
 public Bill adjust(@PathVariable String billNo,@RequestBody AdjustmentRequest r,@RequestHeader("Idempotency-Key")String key){return finance.adjust(new Adjustment(context.bill(r.managementNo(),billNo,"BILL"),billNo,r.clearingNo(),r.expectedRevision(),r.payableDeltaMinor(),r.reason(),key));}
 @PostMapping("/biz/management/stored-value/settlement-bills/{billNo}/payments")
 public Payment register(@PathVariable String billNo,@RequestBody PaymentRequest r,@RequestHeader("Idempotency-Key")String key){if(r.evidenceRefs()!=null&&!r.evidenceRefs().isEmpty())throw BizException.of(ErrorCode.BAD_REQUEST);return finance.register(new Registration(context.bill(r.managementNo(),billNo,"BILL"),billNo,r.expectedRevision(),r.amountMinor(),r.paidAt(),r.method(),r.receiptReference(),List.of(),key));}
 @GetMapping("/biz/management/stored-value/settlement-bills/{billNo}/payment-resolutions")
 public StoredValueFinance.Resolution resolvePayment(@PathVariable String billNo,@RequestParam String managementNo,@RequestParam String commandKey){return finance.resolvePayment(new StoredValueFinance.ResolvePayment(context.bill(managementNo,billNo,"BILL"),billNo,commandKey));}
 @PostMapping("/biz/management/stored-value/settlement-payments/{paymentNo}/receipt-confirmations")
 public Payment confirm(@PathVariable String paymentNo,@RequestBody ReceiptRequest r,@RequestHeader("Idempotency-Key")String key){return finance.confirm(new Receipt(context.bill(r.managementNo(),paymentNo,"PAYMENT"),paymentNo,r.expectedRevision(),r.receivedAt(),key));}
 @PostMapping("/biz/management/stored-value/settlement-payments/{paymentNo}/voids")
 public Payment voidPayment(@PathVariable String paymentNo,@RequestBody VoidRequest r,@RequestHeader("Idempotency-Key")String key){return finance.voidPayment(new VoidPayment(context.bill(r.managementNo(),paymentNo,"PAYMENT"),paymentNo,r.expectedRevision(),r.reason(),key));}
}
