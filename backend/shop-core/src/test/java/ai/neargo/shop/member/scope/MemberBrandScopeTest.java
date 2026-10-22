package ai.neargo.shop.member.scope;
import org.junit.jupiter.api.Test;import static org.assertj.core.api.Assertions.*;import ai.neargo.shop.common.BizException;import java.util.concurrent.*;
class MemberBrandScopeTest {
 @Test void sameBrandDifferentEntitiesShareIdentityButNotPrivateProfile() throws Exception {
  var f=new ScopeFixture();var member=f.members.enroll("MAIN","E1","S1","P1","A","甲店私有备注");
  var second=f.members.require("MAIN","E2","S2",member.scopeMemberNo());assertThat(second.customerNo()).isEqualTo("P1");
  var local=f.query.detail("MAIN","E1","S1",member.scopeMemberNo());var other=f.query.detail("MAIN","E2","S2",member.scopeMemberNo());
  assertThat(local.remark()).isEqualTo("甲店私有备注");assertThat(other.remark()).isNull();assertThat(other.sourceStoreNo()).isEqualTo("S1");
  assertThat(f.members.enroll("MAIN","E2","S2","P1","B",null).scopeMemberNo()).isEqualTo(member.scopeMemberNo());
 }
 @Test void crossBrandAndIndependentStoresAreIsolated() throws Exception {
  var f=new ScopeFixture();var m=f.members.enroll("MAIN","E1","S1","P1","A",null);
  assertThatThrownBy(()->f.members.require("MAIN","E1","S3",m.scopeMemberNo())).isInstanceOf(BizException.class);
  assertThatThrownBy(()->f.members.require("MAIN","E1","S4",m.scopeMemberNo())).isInstanceOf(BizException.class);
  assertThat(f.members.enroll("MAIN","E1","S4","P1","A",null).scopeMemberNo()).isNotEqualTo(m.scopeMemberNo());
 }
 @Test void concurrentEnrollmentHasOneMembershipAndTwoPrivateProfiles() throws Exception {
  var f=new ScopeFixture();var pool=Executors.newFixedThreadPool(2);try{var gate=new CountDownLatch(1);var a=pool.submit(()->{gate.await();return f.members.enroll("MAIN","E1","S1","P1","A",null);});var b=pool.submit(()->{gate.await();return f.members.enroll("MAIN","E2","S2","P1","B",null);});gate.countDown();assertThat(a.get(10,TimeUnit.SECONDS).scopeMemberNo()).isEqualTo(b.get(10,TimeUnit.SECONDS).scopeMemberNo());assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM mbr_store_profile",Long.class)).isEqualTo(2);}finally{pool.shutdownNow();}
 }
 @Test void legacyImportNeedsStoreProvenanceAndDoesNotShareEntityRemarks() throws Exception {
  var f=new ScopeFixture();f.jdbc.update("INSERT INTO mbr_member VALUES('MAIN','OLD1','P1','E1','S1','ACTIVE','私有',0),('MAIN','UNKNOWN','P2','E1',NULL,'ACTIVE','未知',0),('MAIN','OTHER','P3','E1','S3','ACTIVE','另一品牌',0)");
  var page=f.query.search("MAIN","E2","S2","A","138",null,20);
  assertThat(page.items()).hasSize(1);assertThat(page.items().getFirst().remark()).isNull();
  assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM mbr_scope_legacy_link",Long.class)).isEqualTo(1);
 }
 @Test void entityRemarkWithoutStoreAuthorshipIsNeverCopiedIntoStoreProfile() throws Exception {var f=new ScopeFixture();f.jdbc.update("INSERT INTO mbr_member VALUES('MAIN','OLD1','P1','E1','S1','ACTIVE','主体备注可能由另一门店修改',0)");var page=f.query.search("MAIN","E1","S1","A","138",null,20);assertThat(page.items()).hasSize(1);assertThat(page.items().getFirst().remark()).isNull();assertThat(f.jdbc.queryForObject("SELECT remark FROM mbr_member WHERE member_no='OLD1'",String.class)).isEqualTo("主体备注可能由另一门店修改");}
}
