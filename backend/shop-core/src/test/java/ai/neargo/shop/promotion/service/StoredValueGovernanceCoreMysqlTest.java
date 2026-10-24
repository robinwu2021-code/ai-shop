package ai.neargo.shop.promotion.service;
import ai.neargo.shop.promotion.service.StoredValueActivityModels.Decision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.assertj.core.api.Assertions.*;
@EnabledIfSystemProperty(named="p4.core.mysql.url",matches="jdbc:mysql://127.*")
class StoredValueGovernanceCoreMysqlTest {
 @Test void canonicalJsonSnapshotApprovesAndOriginalDecisionReplays()throws Exception{
  var f=new StoredValueGovernanceFixture();var policy=f.policy(true,"b".repeat(64));var saved=f.journal.register(f.submitter,f.view(),policy,"SUBMIT");var decision=new Decision("APPROVE","Reviewed",0);
  var result=f.journal.decide(f.approver,saved.approvalNo(),f.approver.principalNo(),decision,"DECIDE",policy,true);assertThat(result.status()).isEqualTo("APPROVED");assertThat(result.definition().rule().tiers().getFirst().bonusAmountMinor()).isEqualTo(2000);
  assertThat(f.journal.decide(f.approver,saved.approvalNo(),f.approver.principalNo(),decision,"DECIDE",policy,true)).isEqualTo(result);assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM pmt_sv_approval",Long.class)).isEqualTo(1);
 }
 @Test void actualMysqlDraftChangeStalesBothApprovalAndPendingPublication()throws Exception{
  var f=new StoredValueGovernanceFixture();var saved=f.journal.register(f.submitter,f.view(),f.policy(true,"b".repeat(64)),"SUBMIT");f.jdbc.update("UPDATE pmt_activity SET version=1");f.journal.invalidateOlder(f.submitter,"A1");assertThat(f.journal.approval("MAIN","M1",saved.approvalNo()).status()).isEqualTo("STALE");assertThat(f.journal.find(f.submitter,"A1",0).state()).isEqualTo("STALE");
 }
}
