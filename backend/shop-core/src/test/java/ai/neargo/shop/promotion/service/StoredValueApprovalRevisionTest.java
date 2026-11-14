package ai.neargo.shop.promotion.service;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.promotion.service.StoredValueActivityModels.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class StoredValueApprovalRevisionTest {
 @Test void samePlatformPersonCannotApproveUsingAnotherAccount()throws Exception{
  var f=new StoredValueGovernanceFixture();var policy=f.policy(true,"b".repeat(64));var submission=f.journal.register(f.submitter,f.view(),policy,"SUBMIT");
  var alias=new Scope(f.policyScope,"E1","MCH:ALIAS","USER:P1",2);
  assertThatThrownBy(()->f.journal.decide(alias,submission.approvalNo(),alias.principalNo(),new Decision("APPROVE","Review",0),"DECISION",policy,true)).isInstanceOf(BizException.class);
  assertThat(f.journal.approval("MAIN","M1",submission.approvalNo()).status()).isEqualTo("PENDING");
 }
 @Test void draftChangeImmediatelyStalesApprovalAndPublicationGate()throws Exception{
  var f=new StoredValueGovernanceFixture();var submission=f.journal.register(f.submitter,f.view(),f.policy(true,"b".repeat(64)),"SUBMIT");
  f.jdbc.update("UPDATE pmt_activity SET version=1");f.journal.invalidateOlder(f.submitter,"A1");
  assertThat(f.journal.approval("MAIN","M1",submission.approvalNo()).status()).isEqualTo("STALE");
  assertThat(f.journal.find(f.submitter,"A1",0).state()).isEqualTo("STALE");
  assertThatThrownBy(()->f.journal.requirePublish("MAIN","A1",0,submission.draftHash())).isInstanceOf(BizException.class);
 }
 @Test void changedPolicyAndHardBlockNeverProduceApproval()throws Exception{
  var f=new StoredValueGovernanceFixture();var original=f.policy(true,"b".repeat(64));var submission=f.journal.register(f.submitter,f.view(),original,"SUBMIT");
  var stale=f.journal.decide(f.approver,submission.approvalNo(),f.approver.principalNo(),new Decision("APPROVE","Review",0),"DECIDE",f.policy(true,"c".repeat(64)),true);
  assertThat(stale.status()).isEqualTo("STALE");
  var other=new StoredValueGovernanceFixture();var s=other.journal.register(other.submitter,other.view(),original,"SUBMIT");
  assertThat(other.journal.decide(other.approver,s.approvalNo(),other.approver.principalNo(),new Decision("APPROVE","Review",0),"BLOCK",original,false).status()).isEqualTo("BLOCKED_POLICY");
 }
 @Test void originalDecisionReplayPreservesSnapshotAndConflictingRetryIsDenied()throws Exception{
  var f=new StoredValueGovernanceFixture();var policy=f.policy(true,"b".repeat(64));var s=f.journal.register(f.submitter,f.view(),policy,"SUBMIT");var command=new Decision("APPROVE","Reviewed",0);
  var first=f.journal.decide(f.approver,s.approvalNo(),f.approver.principalNo(),command,"DECIDE",policy,true);
  assertThat(first.status()).isEqualTo("APPROVED");assertThat(first.definition().rule().tiers().getFirst().bonusAmountMinor()).isEqualTo(2000);
  assertThat(f.journal.decide(f.approver,s.approvalNo(),f.approver.principalNo(),command,"DECIDE",policy,true)).isEqualTo(first);
  assertThatThrownBy(()->f.journal.decide(f.approver,s.approvalNo(),f.approver.principalNo(),new Decision("RETURN","Other",0),"DECIDE",policy,true)).isInstanceOf(BizException.class);
 }
}
