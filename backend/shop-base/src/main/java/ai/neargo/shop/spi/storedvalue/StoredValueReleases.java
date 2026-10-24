package ai.neargo.shop.spi.storedvalue;
import ai.neargo.shop.spi.entitlement.ContractChecks;
import ai.neargo.shop.spi.storedvalue.StoredValuePolicies.StoreScope;
import java.time.Instant;
import java.util.List;
public final class StoredValueReleases {
 private StoredValueReleases() { }
 public record Tier(String sourceTierNo,long paidMinor,long bonusMinor) {
  public Tier {ContractChecks.id(sourceTierNo);ContractChecks.require(paidMinor>0&&bonusMinor>=0);ContractChecks.addAmounts(paidMinor,bonusMinor);}
 }
 public record Prepare(StoreScope scope,String entityNo,String actorNo,String submittedBy,String name,String activityNo,String draftRevisionNo,String draftHash,Instant startsAt,Instant endsAt,String zone,List<Tier> tiers,String key) {
  public Prepare {ContractChecks.require(name!=null&&!name.isBlank()&&name.length()<=100);ContractChecks.present(scope);for(String v:List.of(entityNo,actorNo,submittedBy,activityNo,draftRevisionNo,key))ContractChecks.id(v);ContractChecks.hash(draftHash);ContractChecks.present(startsAt);ContractChecks.present(endsAt);ContractChecks.require(endsAt.isAfter(startsAt));java.time.ZoneId.of(zone);tiers=List.copyOf(tiers);ContractChecks.require(!tiers.isEmpty()&&tiers.size()<=50&&tiers.stream().map(Tier::sourceTierNo).distinct().count()==tiers.size()&&tiers.stream().map(Tier::paidMinor).distinct().count()==tiers.size());}
 }
 public record Check(ai.neargo.shop.spi.storedvalue.StoredValuePolicies.EffectivePolicy policy,boolean allowed,boolean approvalRequired,List<String> blockers){public Check{blockers=List.copyOf(blockers);}}
 public record Approval(String draftHash,String policyHash,String submittedBy,String decidedBy) {
  public Approval {ContractChecks.hash(draftHash);ContractChecks.hash(policyHash);ContractChecks.id(submittedBy);ContractChecks.id(decidedBy);ContractChecks.require(!submittedBy.equals(decidedBy));}
 }
 public record Activate(StoreScope scope,String entityNo,String actorNo,String releaseNo,String activityNo,String draftRevisionNo,String draftHash,String releaseHash,String activityVersionNo,String activityVersionHash,Approval approval,String key) {
  public Activate {ContractChecks.present(scope);for(String v:List.of(entityNo,actorNo,releaseNo,activityNo,draftRevisionNo,activityVersionNo,key))ContractChecks.id(v);ContractChecks.hash(draftHash);ContractChecks.hash(releaseHash);ContractChecks.hash(activityVersionHash);}
 }
 public record Stop(StoreScope scope,String entityNo,String actorNo,String releaseNo,String key) {
  public Stop {ContractChecks.present(scope);for(String v:List.of(entityNo,actorNo,releaseNo,key))ContractChecks.id(v);}
 }
 public record View(String releaseNo,String issuerNo,String state,String contentHash,String policyHash,boolean approvalRequired,String activityVersionNo,List<Tier> tiers) {public View{tiers=List.copyOf(tiers);}}
}
