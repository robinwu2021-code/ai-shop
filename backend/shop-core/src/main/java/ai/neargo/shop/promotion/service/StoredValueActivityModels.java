package ai.neargo.shop.promotion.service;
import ai.neargo.shop.promotion.dto.ActivityActor;
import ai.neargo.shop.spi.entitlement.ContractChecks;
import ai.neargo.shop.spi.storedvalue.StoredValuePolicies.StoreScope;
import ai.neargo.shop.spi.storedvalue.StoredValueReleases.Approval;
import java.util.Set;
public final class StoredValueActivityModels {
 private StoredValueActivityModels() { }
 public record Scope(StoreScope policyScope,String entityNo,String actorNo,String principalNo,long authorityRevision) {
  public Scope {ContractChecks.present(policyScope);ContractChecks.id(entityNo);ContractChecks.id(actorNo);ContractChecks.id(principalNo);ContractChecks.require(authorityRevision>=0);}
  public ActivityActor actor(){return new ActivityActor(policyScope.tenantNo(),entityNo,actorNo,Set.of(policyScope.storeNo()));}
 }
 public record Submission(String linkNo,String activityNo,String draftRevisionNo,String draftHash,String state,String releaseNo,String releaseHash,String policyHash,boolean approvalRequired,String approvalNo,String activityVersionNo) { }
 public record Decision(String decision,String reason,long expectedRevision) {
  public Decision {ContractChecks.require(Set.of("APPROVE","RETURN").contains(decision));ContractChecks.require(reason!=null&&!reason.isBlank()&&reason.length()<=255&&expectedRevision>=0);}
 }
 public record ApprovalView(String approvalNo,String linkNo,String activityNo,String draftRevisionNo,String draftHash,String storeNo,String managementNo,String status,String policyHash,String submittedBy,String decidedBy,long revision,String reason,ai.neargo.shop.promotion.dto.UnifiedActivityDefinition definition) { }
 public record Approved(Submission submission,Approval approval) { }
}
