package ai.neargo.shop.spi.storedvalue;
import ai.neargo.shop.spi.entitlement.ContractChecks;
import java.util.*;
/** Frozen Core-authorized financial membership command. Not a public request DTO. */
public final class StoredValueMembership {
 private StoredValueMembership(){}
 /** Build-level capability, raised only when the Core saga and central-collection proof are implemented and verified. */
 public static final int COORDINATION_IMPLEMENTATION_PROTOCOL=0;
 public record Describe(String tenantNo,String actorNo,String managementNo,String storeNo,String operatingEntityNo,List<String> brandNos){public Describe{for(var s:List.of(tenantNo,actorNo,managementNo,storeNo,operatingEntityNo))ContractChecks.id(s);brandNos=List.copyOf(brandNos);ContractChecks.require(!brandNos.isEmpty()&&brandNos.size()<=2&&brandNos.stream().distinct().count()==brandNos.size());for(var b:brandNos)ContractChecks.id(b);}}
 public record Target(String issuerNo,String issuerVersionNo,String brandNo,String settlementEntityNo,String currency,String bindingNo,long bindingRevision,String accessStatus){}
 public record Side(String issuerNo,String issuerVersionNo,String brandNo,String direction,String oldBindingNo,String newBindingNo,long oldRevision,long newRevision){public Side{for(var s:List.of(issuerNo,issuerVersionNo,brandNo))ContractChecks.id(s);ContractChecks.optionalId(oldBindingNo);ContractChecks.optionalId(newBindingNo);ContractChecks.require(Set.of("LEAVE","JOIN").contains(direction)&&newRevision>oldRevision&&oldRevision>=-1);ContractChecks.require("JOIN".equals(direction)?newBindingNo!=null:oldBindingNo!=null);}}
 public record Prepare(String tenantNo,String actorNo,String managementNo,String changeNo,String storeNo,String operatingEntityNo,List<Side> sides,int coreProtocol){public Prepare{for(var s:List.of(tenantNo,actorNo,managementNo,changeNo,storeNo,operatingEntityNo))ContractChecks.id(s);sides=List.copyOf(sides);ContractChecks.require(coreProtocol>=2&&!sides.isEmpty()&&sides.size()<=2&&sides.stream().map(Side::issuerNo).distinct().count()==sides.size());}}
 public record Ack(String changeNo,String snapshotHash,String state,List<String> issuerNos){public Ack{issuerNos=List.copyOf(issuerNos);}}
 public record Commit(Prepare original,String preparedHash,String coreCommitNo){public Commit{Objects.requireNonNull(original);ContractChecks.require(preparedHash!=null&&preparedHash.length()==64);ContractChecks.id(coreCommitNo);ContractChecks.require(coreCommitNo.equals(original.changeNo()));}}
}
