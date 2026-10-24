package ai.neargo.shop.spi.storedvalue;
import ai.neargo.shop.spi.entitlement.ContractChecks;
import java.time.*;
import java.util.*;
/** Trusted authorization is constructed by Merchant/BFF, never deserialized from a public request body. */
public final class StoredValueSettlements {
 private StoredValueSettlements(){}
 public record IssuerGrant(String issuerNo,String scopeType,String scopeNo){public IssuerGrant{ContractChecks.id(issuerNo);ContractChecks.id(scopeNo);ContractChecks.require(Set.of("STORE","BRAND").contains(scopeType));}}
 public record Authority(String tenantNo,String actorNo,String principalNo,String managementNo,List<IssuerGrant> issuers,Map<String,Set<String>> entityPermissions,Set<String> verifiedEvidenceRefs){
  public Authority{for(String s:List.of(tenantNo,actorNo,principalNo,managementNo))ContractChecks.id(s);issuers=List.copyOf(issuers);ContractChecks.require(!issuers.isEmpty()&&issuers.size()<=1000);var copy=new TreeMap<String,Set<String>>();entityPermissions.forEach((key,value)->{ContractChecks.id(key);copy.put(key,Set.copyOf(value));});entityPermissions=Collections.unmodifiableMap(copy);verifiedEvidenceRefs=Set.copyOf(verifiedEvidenceRefs);}
  public boolean can(String entity,String permission){return entityPermissions.getOrDefault(entity,Set.of()).contains(permission);}
  public boolean owns(String issuer){return issuers.stream().anyMatch(i->i.issuerNo().equals(issuer));}
 }
 public record Bill(String billNo,String issuerNo,String payerEntityNo,String payeeEntityNo,String currency,Instant periodFrom,Instant periodTo,String billKind,String status,
  long payableMinor,long registeredPaymentMinor,long confirmedReceiptMinor,long principalMinor,long bonusMinor,long sourceCount,long sourceWatermark,
  String payerReconciledBy,String payeeReconciledBy,String disputeNo,long revision,Instant createdAt,Instant updatedAt){}
 public record Item(String itemNo,String clearingNo,long amountMinor,long principalMinor,long bonusMinor,String rechargeStoreNo,String redemptionStoreNo,Instant occurredAt,Instant postedAt,boolean carriedForward,String entryType,String originalClearingNo){}
 public record Event(String eventNo,String type,long billRevision,String actorNo,String reason,Instant occurredAt){}
 public record Payment(String paymentNo,String billNo,String currency,String method,long amountMinor,Instant paidAt,String status,String registeredBy,String receivedBy,Instant receivedAt,long revision){}
 public record Location(String billNo,String issuerNo,String payerEntityNo,String payeeEntityNo,String currency){}
 public record Query(Authority authority,String status,StoredValueReports.Period period,String cursor,int size,String currency){public Query(Authority authority,String status,StoredValueReports.Period period,String cursor,int size){this(authority,status,period,cursor,size,null);} public Query{if(currency!=null)ContractChecks.currency(currency);Objects.requireNonNull(authority);ContractChecks.require(size>0&&size<=100);}}
 public record Generation(Authority authority,String issuerNo,StoredValueReports.Period period,String currency,String commandKey){public Generation{Objects.requireNonNull(authority);Objects.requireNonNull(period);ContractChecks.id(issuerNo);ContractChecks.id(commandKey);ContractChecks.currency(currency);}}
 public record Generated(List<Bill> bills,long sourceWatermark){public Generated{bills=List.copyOf(bills);}}
 public record Decision(Authority authority,String billNo,long expectedRevision,String side,String reason,String commandKey){public Decision{Objects.requireNonNull(authority);ContractChecks.id(billNo);ContractChecks.id(commandKey);ContractChecks.require(expectedRevision>=0&&(reason==null||!reason.isBlank()&&reason.length()<=255));ContractChecks.require(side==null||Set.of("PAYER","PAYEE").contains(side));}}
 public record Resolution(Authority authority,String billNo,long expectedRevision,String disputeNo,String decision,String reason,String commandKey){public Resolution{Objects.requireNonNull(authority);ContractChecks.id(billNo);ContractChecks.id(disputeNo);ContractChecks.id(commandKey);ContractChecks.require(expectedRevision>=0&&decision!=null&&Set.of("ACCEPT","REJECT").contains(decision)&&reason!=null&&!reason.isBlank()&&reason.length()<=255);}}
 public record Adjustment(Authority authority,String billNo,String clearingNo,long expectedRevision,long payableDeltaMinor,String reason,String commandKey){public Adjustment{Objects.requireNonNull(authority);ContractChecks.id(billNo);ContractChecks.id(clearingNo);ContractChecks.id(commandKey);ContractChecks.require(expectedRevision>=0&&payableDeltaMinor!=0&&reason!=null&&!reason.isBlank()&&reason.length()<=255);}}
 public record Registration(Authority authority,String billNo,long expectedRevision,long amountMinor,Instant paidAt,String method,String receiptReference,List<String> evidenceRefs,String commandKey){
  public Registration{Objects.requireNonNull(authority);ContractChecks.id(billNo);ContractChecks.id(commandKey);ContractChecks.require(expectedRevision>=0&&amountMinor>0&&paidAt!=null&&receiptReference!=null&&!receiptReference.isBlank()&&receiptReference.length()<=160);ContractChecks.require(method!=null&&Set.of("TRANSFER","CASH","OTHER").contains(method));evidenceRefs=evidenceRefs==null?List.of():List.copyOf(evidenceRefs);ContractChecks.require(evidenceRefs.size()<=10);for(String ref:evidenceRefs)ContractChecks.id(ref);}
  @Override public String toString(){return "Registration[billNo="+billNo+",receiptReference=REDACTED,evidenceRefs=REDACTED]";}
 }
 public record Receipt(Authority authority,String paymentNo,long expectedRevision,Instant receivedAt,String commandKey){public Receipt{Objects.requireNonNull(authority);ContractChecks.id(paymentNo);ContractChecks.id(commandKey);ContractChecks.require(expectedRevision>=0&&receivedAt!=null);}}
 public record VoidPayment(Authority authority,String paymentNo,long expectedRevision,String reason,String commandKey){public VoidPayment{Objects.requireNonNull(authority);ContractChecks.id(paymentNo);ContractChecks.id(commandKey);ContractChecks.require(expectedRevision>=0&&reason!=null&&!reason.isBlank()&&reason.length()<=255);}}
 public record Resource(String tenantNo,String resourceNo,String kind){public Resource{ContractChecks.id(tenantNo);ContractChecks.id(resourceNo);ContractChecks.require(kind!=null&&Set.of("BILL","PAYMENT").contains(kind));}}
 public record Capability(int protocol,boolean ready,List<String> blockers){public Capability{blockers=List.copyOf(blockers);}}
}
