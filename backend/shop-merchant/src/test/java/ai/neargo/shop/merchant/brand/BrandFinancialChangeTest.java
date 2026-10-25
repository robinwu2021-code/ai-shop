package ai.neargo.shop.merchant.brand;
import ai.neargo.shop.merchant.brand.impl.BrandFinancialChangeServiceImpl;
import static ai.neargo.shop.merchant.brand.ManagementModels.*;
import ai.neargo.shop.spi.storedvalue.BrandChangeImpactPort;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.spi.storedvalue.StoredValueMembership.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class BrandFinancialChangeTest {
 record F(BrandFixture f,String management,String brand,BrandFinancialChangeService journal){}
 F fixture()throws Exception{
  var f=new BrandFixture();String sql=Files.readString(Path.of("../shop-app/src/main/resources/db/migration/V576__stored_value_financial_membership_saga.sql"));sql=sql.substring(0,sql.indexOf("CREATE TABLE sv_financial_membership_change")).replaceAll("(?m)^--.*$","").replaceAll(" ENGINE=InnoDB[^;]*","").replace(" JSON"," VARCHAR(30000)").replace(" STORED","").replace("status IN ('PREPARING','PAY_PREPARED')","CASE status WHEN 'PREPARING' THEN TRUE WHEN 'PAY_PREPARED' THEN TRUE ELSE FALSE END").replace("status IN ('PREPARING','PAY_PREPARED','COMPLETED','REJECTED')","CASE status WHEN 'PREPARING' THEN TRUE WHEN 'PAY_PREPARED' THEN TRUE WHEN 'COMPLETED' THEN TRUE WHEN 'REJECTED' THEN TRUE ELSE FALSE END");for(var part:sql.split(";"))if(!part.isBlank())f.jdbc.execute(part);
  String m=f.no(f.space(),"managementNo");var brands=new ai.neargo.shop.merchant.brand.impl.BrandServiceImpl(f.repo,f.scopes,new ManagementTokenCodec("financial-change-secret-long-enough-for-tests",f.repo.json),(a,e,s)->new BrandChangeImpactPort.Impact(true,false,0,0,0,0),f.numbers());String b=f.no(brands.create(f.owner,new BrandCreate(m,"Brand",null,"ACTIVE"),"CREATE"),"brandNo");
  for(String permission:List.of("store.read","store.brand.change"))f.scopes.grant(f.owner,m,new GrantCommand(f.owner.actorKey(),"STORE","S1",permission,null,null,false,null,false));
  return new F(f,m,b,new BrandFinancialChangeServiceImpl(f.repo,f.scopes,f.numbers()));
 }
 BrandFinancialChangeService.Pending register(F f){return f.journal.register(f.f.owner,f.management,"S1",f.brand,0,"join brand","JOIN",List.of(new Target("I1","IV1",f.brand,"E1","CNY",null,-1,null)));}
 Ack ack(F f,BrandFinancialChangeService.Pending p){return new Ack(p.changeNo(),f.journal.expectedPayHash(p.command()),"PREPARED",List.of("I1"));}
 @Test void unrelatedProfileEditAfterPayPreparedDoesNotStrandOriginal()throws Exception{
  var f=fixture();var p=register(f);var a=ack(f,p);f.journal.prepared(f.f.owner,f.management,p.changeNo(),a);f.f.jdbc.update("UPDATE mch_store SET name='Updated banner',version=version+1 WHERE store_no='S1'");f.journal.stage(f.f.owner,f.management,p.changeNo());assertThat(f.journal.complete(f.f.owner,f.management,p.changeNo(),new Ack(a.changeNo(),a.snapshotHash(),"ACTIVE",a.issuerNos())).state()).isEqualTo("COMPLETED");
 }
 @Test void brandMembershipPreservesIndependentStoreIssuerSourceRevision()throws Exception{
  var f=fixture();long original=f.f.jdbc.queryForObject("SELECT version FROM mch_store WHERE store_no='S1'",Long.class);complete(f,register(f));assertThat(f.f.jdbc.queryForObject("SELECT version FROM mch_store WHERE store_no='S1'",Long.class)).isEqualTo(original);
 }
 @Test void membershipIsFencedUntilPayCommitAndOriginalCanResume()throws Exception{
  var f=fixture();var p=register(f);assertThat(register(f)).isEqualTo(p);var a=ack(f,p);f.journal.prepared(f.f.owner,f.management,p.changeNo(),a);f.journal.stage(f.f.owner,f.management,p.changeNo());
  assertThat(f.f.jdbc.queryForObject("SELECT COUNT(*) FROM mch_brand_store_binding WHERE store_no='S1' AND status='ACTIVE'",Long.class)).isZero();assertThatThrownBy(()->new ai.neargo.shop.merchant.brand.impl.StoredValueExecutionScopeServiceImpl(f.f.repo).current("MAIN","MCH:MA1","E1","S1","CNY")).isInstanceOf(BizException.class);
  assertThatThrownBy(()->new ai.neargo.shop.merchant.port.MemberOperatingScopePortImpl(f.f.jdbc).resolve("MAIN","E1","S1")).isInstanceOf(BizException.class);
  var resumed=f.journal.original(f.f.owner,f.management,p.changeNo());assertThat(resumed.staged()).isTrue();var done=f.journal.complete(f.f.owner,f.management,p.changeNo(),new Ack(a.changeNo(),a.snapshotHash(),"ACTIVE",a.issuerNos()));assertThat(done.state()).isEqualTo("COMPLETED");assertThat(f.journal.complete(f.f.owner,f.management,p.changeNo(),new Ack(a.changeNo(),a.snapshotHash(),"ACTIVE",a.issuerNos()))).isEqualTo(done);assertThat(new ai.neargo.shop.merchant.brand.impl.StoredValueExecutionScopeServiceImpl(f.f.repo).current("MAIN","MCH:MA1","E1","S1","CNY").brandNo()).isEqualTo(f.brand);
 }
 @Test void wrongAckAndConcurrentStoreChangeCannotSwitchMembership()throws Exception{
  var f=fixture();var p=register(f);assertThatThrownBy(()->f.journal.prepared(f.f.owner,f.management,p.changeNo(),new Ack(p.changeNo(),"x".repeat(64),"PREPARED",List.of("I1")))).isInstanceOf(BizException.class);
  assertThatThrownBy(()->f.journal.register(f.f.owner,f.management,"S1",f.brand,0,"other","OTHER",List.of(new Target("I1","IV1",f.brand,"E1","CNY",null,-1,null)))) .isInstanceOf(BizException.class);assertThat(f.f.jdbc.queryForObject("SELECT version FROM mch_store WHERE store_no='S1'",Long.class)).isZero();
 }
 @Test void foreignActorCannotResumeFinancialOrganizationChange()throws Exception{
  var f=fixture();var p=register(f);assertThatThrownBy(()->f.journal.original(f.f.other,f.management,p.changeNo())).isInstanceOf(BizException.class);
 }
 BrandFinancialChangeService.Pending complete(F f,BrandFinancialChangeService.Pending p){var a=new Ack(p.changeNo(),f.journal.expectedPayHash(p.command()),"PREPARED",p.command().sides().stream().map(Side::issuerNo).sorted().toList());f.journal.prepared(f.f.owner,f.management,p.changeNo(),a);f.journal.stage(f.f.owner,f.management,p.changeNo());return f.journal.complete(f.f.owner,f.management,p.changeNo(),new Ack(a.changeNo(),a.snapshotHash(),"ACTIVE",a.issuerNos()));}
 @Test void leavingBrandKeepsTheOldScopeFencedUntilFinalAcknowledgement()throws Exception{
  var f=fixture();var joined=register(f);complete(f,joined);var side=joined.command().sides().getFirst();var leaving=f.journal.register(f.f.owner,f.management,"S1",null,0,"leave brand","LEAVE",List.of(new Target("I1","IV1",f.brand,"E1","CNY",side.newBindingNo(),side.newRevision(),"ACTIVE")));var a=ack(f,leaving);f.journal.prepared(f.f.owner,f.management,leaving.changeNo(),a);f.journal.stage(f.f.owner,f.management,leaving.changeNo());assertThatThrownBy(()->new ai.neargo.shop.merchant.brand.impl.StoredValueExecutionScopeServiceImpl(f.f.repo).current("MAIN","MCH:MA1","E1","S1","CNY")).isInstanceOf(BizException.class);f.journal.complete(f.f.owner,f.management,leaving.changeNo(),new Ack(a.changeNo(),a.snapshotHash(),"ACTIVE",a.issuerNos()));assertThat(new ai.neargo.shop.merchant.brand.impl.StoredValueExecutionScopeServiceImpl(f.f.repo).current("MAIN","MCH:MA1","E1","S1","CNY").brandNo()).isNull();
 }
 @Test void switchingBrandCreatesOneNewBindingAndPreservesEndedHistory()throws Exception{
  var f=fixture();var joined=register(f);complete(f,joined);var first=joined.command().sides().getFirst();var brands=new ai.neargo.shop.merchant.brand.impl.BrandServiceImpl(f.f.repo,f.f.scopes,new ManagementTokenCodec("financial-change-secret-long-enough-for-tests",f.f.repo.json),(a,e,t)->new BrandChangeImpactPort.Impact(true,false,0,0,0,0),f.f.numbers());String next=f.f.no(brands.create(f.f.owner,new BrandCreate(f.management,"Other brand",null,"ACTIVE"),"OTHER-BRAND"),"brandNo");
  var p=f.journal.register(f.f.owner,f.management,"S1",next,0,"switch","SWITCH",List.of(new Target("I1","IV1",f.brand,"E1","CNY",first.newBindingNo(),first.newRevision(),"ACTIVE"),new Target("I2","IV2",next,"E2","CNY",null,-1,null)));complete(f,p);assertThat(new ai.neargo.shop.merchant.brand.impl.StoredValueExecutionScopeServiceImpl(f.f.repo).current("MAIN","MCH:MA1","E1","S1","CNY").brandNo()).isEqualTo(next);assertThat(f.f.jdbc.queryForObject("SELECT COUNT(*) FROM mch_brand_store_binding WHERE store_no='S1' AND status='ENDED'",Long.class)).isEqualTo(1);assertThat(f.f.jdbc.queryForObject("SELECT COUNT(*) FROM mch_brand_store_binding WHERE store_no='S1' AND status='ACTIVE'",Long.class)).isEqualTo(1);
 }
}
