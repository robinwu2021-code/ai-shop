package ai.neargo.shop.member.scope;
import org.junit.jupiter.api.Test;import static org.assertj.core.api.Assertions.*;import static org.mockito.Mockito.*;import ai.neargo.shop.common.BizException;import ai.neargo.shop.spi.user.MemberIdentityFactsPort;import java.util.*;
class MemberIdentityConflictTest {
 @Test void phoneCollisionDoesNotMergeCanonicalPeople() throws Exception {var f=new ScopeFixture();var a=f.members.enroll("MAIN","E1","S1","P1","A",null);var b=f.members.enroll("MAIN","E2","S2","P2","B",null);assertThat(a.scopeMemberNo()).isNotEqualTo(b.scopeMemberNo());assertThat(f.query.search("MAIN","E1","S1","A","138",null,20).items()).hasSize(2);}
 @Test void inconsistentUserBindingCannotEnrollOrReplaceExistingIdentity() throws Exception {var f=new ScopeFixture();when(f.identities.find("MAIN","P1")).thenReturn(Optional.of(new MemberIdentityFactsPort.Identity("P1","U-other","138****8000",false)));assertThatThrownBy(()->f.members.enroll("MAIN","E1","S1","P1","A",null)).isInstanceOf(BizException.class);assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM mbr_scope_member",Long.class)).isZero();}
 @Test void cursorCannotCrossActorOrBrandRevision() throws Exception {var f=new ScopeFixture();for(String p:List.of("P1","P2"))f.members.enroll("MAIN","E1","S1",p,"A",null);var first=f.query.search("MAIN","E1","S1","A","138",null,1);assertThat(first.nextCursor()).isNotNull();assertThatThrownBy(()->f.query.search("MAIN","E1","S1","B","138",first.nextCursor(),1)).isInstanceOf(BizException.class);}
 @Test void unresolvedLegacyIdentityIsReportedWithoutCreatingMembership() throws Exception {var f=new ScopeFixture();f.jdbc.update("INSERT INTO mbr_member VALUES('MAIN','OLD1','P1','E1','S1','ACTIVE','私有',0)");when(f.identities.find("MAIN","P1")).thenReturn(Optional.of(new MemberIdentityFactsPort.Identity("P1","U-other","138****8000",false)));var result=f.query.search("MAIN","E2","S2","A","138",null,20);assertThat(result.identityConflictCount()).isEqualTo(1);assertThat(result.items()).isEmpty();assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM mbr_scope_member",Long.class)).isZero();}
 @Test void verifiedAccountClaimPreservesCustomerAndNeverUnblocksMembership() throws Exception {var f=new ScopeFixture();var active=f.members.enroll("MAIN","E1","S1","P1","A","本店资料");var blocked=f.members.enroll("MAIN","E1","S4","P1","A",null);f.jdbc.update("UPDATE mbr_scope_member SET status='BLOCKED' WHERE scope_member_no=?",blocked.scopeMemberNo());when(f.identities.find("MAIN","P1")).thenReturn(Optional.of(new MemberIdentityFactsPort.Identity("P1","U1","138****8000",true)));f.members.claim("MAIN","P1");assertThat(f.members.require("MAIN","E2","S2",active.scopeMemberNo()).status()).isEqualTo("ACTIVE");assertThat(f.members.require("MAIN","E1","S4",blocked.scopeMemberNo()).status()).isEqualTo("BLOCKED");assertThat(f.jdbc.queryForObject("SELECT COUNT(DISTINCT customer_no) FROM mbr_scope_member",Long.class)).isEqualTo(1);}
 @Test void linkedLegacyBlockingIsCurrentAndRestorationDoesNotMergeOrOverwriteMembership() throws Exception {var f=new ScopeFixture();f.jdbc.update("INSERT INTO mbr_member VALUES('MAIN','OLD1','P1','E1','S1','ACTIVE',NULL,0)");var m=f.query.search("MAIN","E2","S2","A","138",null,20).items().getFirst();f.jdbc.update("UPDATE mbr_member SET status='BLOCKED' WHERE member_no='OLD1'");assertThat(f.members.require("MAIN","E1","S1",m.scopeMemberNo()).status()).isEqualTo("BLOCKED");assertThat(f.members.require("MAIN","E2","S2",m.scopeMemberNo()).status()).isEqualTo("LEAD");f.jdbc.update("UPDATE mbr_member SET status='ACTIVE' WHERE member_no='OLD1'");assertThat(f.members.require("MAIN","E2","S2",m.scopeMemberNo()).status()).isEqualTo("LEAD");}
 @Test void provenanceLessLegacyBlockStillAppliesOnlyToExecutingEntity() throws Exception {
  var f=new ScopeFixture();
  f.jdbc.update("INSERT INTO mbr_member VALUES('MAIN','OLD1','P1','E1',NULL,'BLOCKED',NULL,0)");
  var member=f.members.enroll("MAIN","E2","S2","P1","B",null);
  assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM mbr_scope_legacy_link",Long.class)).isZero();
  assertThat(f.members.require("MAIN","E1","S1",member.scopeMemberNo()).status()).isEqualTo("BLOCKED");
  assertThat(f.members.require("MAIN","E2","S2",member.scopeMemberNo()).status()).isEqualTo("LEAD");
  f.jdbc.update("UPDATE mbr_member SET status='ACTIVE' WHERE member_no='OLD1'");
  assertThat(f.members.require("MAIN","E1","S1",member.scopeMemberNo()).status()).isEqualTo("LEAD");
 }
 @Test void invalidIdentityRemarkDoesNotBreakOtherSearchResults() throws Exception {
  var f=new ScopeFixture();
  var unavailable=f.members.enroll("MAIN","E1","S1","P1","A","张先生");
  var valid=f.members.enroll("MAIN","E1","S1","P2","A","李先生");
  when(f.identities.find("MAIN","P1")).thenReturn(Optional.empty());
  var page=f.query.search("MAIN","E1","S1","A","先生",null,20);
  assertThat(page.items()).extracting(x->x.scopeMemberNo()).containsExactly(valid.scopeMemberNo());
  assertThat(page.identityConflictCount()).isEqualTo(1);
  assertThatThrownBy(()->f.members.require("MAIN","E1","S1",unavailable.scopeMemberNo())).isInstanceOf(BizException.class);
  when(f.identities.find("MAIN","P1")).thenReturn(Optional.of(new MemberIdentityFactsPort.Identity("P1","U-other","138****8000",false)));
  assertThat(f.query.search("MAIN","E1","S1","A","先生",null,20).items()).extracting(x->x.scopeMemberNo()).containsExactly(valid.scopeMemberNo());
 }
}
