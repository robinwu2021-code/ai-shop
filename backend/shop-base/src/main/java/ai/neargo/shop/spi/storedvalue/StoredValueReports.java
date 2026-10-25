package ai.neargo.shop.spi.storedvalue;
import ai.neargo.shop.spi.entitlement.ContractChecks;
import java.time.*;
import java.util.*;
/** Core-authorized read scope. Neither authority nor internal account IDs are accepted from a public client. */
public final class StoredValueReports {
 private StoredValueReports(){}
 public record Scope(String tenantNo,String actorNo,String managementNo,String issuerNo,String scopeType,String scopeNo,String currency,String activityNo,String occurrenceStoreNo){
  public Scope{for(String s:List.of(tenantNo,actorNo,managementNo,issuerNo,scopeType,scopeNo))ContractChecks.id(s);ContractChecks.currency(currency);ContractChecks.require(Set.of("STORE","BRAND").contains(scopeType));ContractChecks.optionalId(activityNo);ContractChecks.optionalId(occurrenceStoreNo);}
 }
 public record Period(Instant from,Instant to){public Period{ContractChecks.require(from!=null&&to!=null&&from.isBefore(to)&&Duration.between(from,to).compareTo(Duration.ofDays(367))<=0);}}
 public record Summary(String issuerNo,String scopeType,String name,String currency,Period period,Instant asOf,long sourceWatermark,
  long openingMinor,long creditedPaidMinor,long creditedBonusMinor,long consumedMinor,long reversedMinor,long closingMinor,long heldMinor,
  long rechargeCount,long usageCount,long customerCount,long payableMinor){
  public Summary{ContractChecks.require(Math.addExact(Math.addExact(openingMinor,Math.addExact(creditedPaidMinor,creditedBonusMinor)),Math.subtractExact(reversedMinor,consumedMinor))==closingMinor);}
 }
 public record Projection(Scope scope,LocalDate date,String afterRelease,String afterStore,int size){public Projection{Objects.requireNonNull(scope);Objects.requireNonNull(date);ContractChecks.require(size>0&&size<=500&&(afterRelease==null)==(afterStore==null));ContractChecks.optionalId(afterRelease);ContractChecks.optionalId(afterStore);}}
 public record ProjectionResult(String issuerNo,LocalDate date,String afterRelease,String afterStore,boolean hasMore,int rows,Instant projectedAt){}
 public record StoreAttribution(String storeNo,String storeName,long creditedPaidMinor,long creditedBonusMinor,long consumedMinor,long payableMinor,long rechargeCount,long usageCount,long customerCount,Instant asOf,long sourceWatermark){}
 public record ActivityAttribution(String activityNo,String releaseNo,String sourceKind,String name,long creditedPaidMinor,long creditedBonusMinor,long consumedPrincipalMinor,long consumedBonusMinor,long remainingMinor,long rechargeCount,long usageCount,long customerCount,Instant asOf,long sourceWatermark){}
 public record Query(Scope scope,Period period,String cursor,int size){public Query{Objects.requireNonNull(scope);Objects.requireNonNull(period);ContractChecks.require(size>0&&size<=100);}}
}
