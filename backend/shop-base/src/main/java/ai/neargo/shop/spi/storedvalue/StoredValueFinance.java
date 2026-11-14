package ai.neargo.shop.spi.storedvalue;
import ai.neargo.shop.spi.entitlement.ContractChecks;
import java.util.*;
/** Thin resource metadata and invocation wrappers for the trusted Core/Pay boundary. No collection routes are exposed. */
public final class StoredValueFinance {
 private StoredValueFinance(){}
 public record Resource(String issuerNo,String scopeType,String scopeNo,String name,String currency,String businessTimeZone){}
 public record Directory(String tenantNo,String actorNo,String managementNo,String purpose,List<StoredValueIssuers.ScopeRef> scopes,Set<String> entityNos){public Directory{for(String s:List.of(tenantNo,actorNo,managementNo))ContractChecks.id(s);ContractChecks.require(purpose!=null&&Set.of("BUSINESS","FINANCE").contains(purpose));scopes=List.copyOf(scopes);entityNos=Set.copyOf(entityNos);ContractChecks.require(scopes.size()<=1000&&entityNos.size()<=1000);for(var r:scopes){ContractChecks.require(Set.of("STORE","BRAND").contains(r.scopeType()));ContractChecks.id(r.scopeNo());}}}
 public record SummaryRequest(StoredValueReports.Scope scope,StoredValueReports.Period period){public SummaryRequest{Objects.requireNonNull(scope);Objects.requireNonNull(period);}}
 public record BatchReports(List<StoredValueReports.Scope> scopes,StoredValueReports.Period period,String cursor,int size,Map<String,StoredValueReports.Period> issuerPeriods){public BatchReports(List<StoredValueReports.Scope> scopes,StoredValueReports.Period period,String cursor,int size){this(scopes,period,cursor,size,Map.of());} public BatchReports{issuerPeriods=Map.copyOf(issuerPeriods);ContractChecks.require(issuerPeriods.size()<=1000&&scopes.stream().map(StoredValueReports.Scope::issuerNo).collect(java.util.stream.Collectors.toSet()).containsAll(issuerPeriods.keySet()));scopes=List.copyOf(scopes);Objects.requireNonNull(period);ContractChecks.require(scopes.size()<=1000&&size>0&&size<=100);if(!scopes.isEmpty()){var first=scopes.getFirst();for(var s:scopes)ContractChecks.require(s.tenantNo().equals(first.tenantNo())&&s.actorNo().equals(first.actorNo())&&s.managementNo().equals(first.managementNo())&&s.scopeType().equals("STORE"));}}}
 public record BillRead(StoredValueSettlements.Authority authority,String billNo,String cursor,int size){public BillRead{Objects.requireNonNull(authority);ContractChecks.id(billNo);ContractChecks.require(size>0&&size<=100);}}
 public record ResolvePayment(StoredValueSettlements.Authority authority,String billNo,String commandKey){public ResolvePayment{Objects.requireNonNull(authority);ContractChecks.id(billNo);ContractChecks.id(commandKey);}}
 public record Resolution(boolean found,StoredValueSettlements.Payment payment){}
 public record Capabilities(int protocol,boolean reportsReady,boolean settlementReady,boolean crossEntityReady,boolean receiptAttachmentsSupported,List<String> blockers){public Capabilities{blockers=List.copyOf(blockers);}}
}
