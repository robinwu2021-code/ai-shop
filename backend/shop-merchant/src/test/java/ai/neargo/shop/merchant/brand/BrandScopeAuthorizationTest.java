package ai.neargo.shop.merchant.brand;
import org.junit.jupiter.api.Test;
import java.time.*;
import static org.assertj.core.api.Assertions.*;
class BrandScopeAuthorizationTest {
 @Test void spaceOwnershipDoesNotGrantOtherEntity() throws Exception {
  var f=new BrandFixture();var space=f.space();var m=f.no(space,"managementNo");
  assertThat(f.scopes.can(f.owner,m,"ENTITY","E2","entity.read")).isFalse();
  assertThatThrownBy(()->f.scopes.grant(f.owner,m,new ManagementModels.GrantCommand("MCH:MA1","ENTITY","E2","entity.read",null,null,false,null,false))).isInstanceOf(ai.neargo.shop.common.BizException.class);
 }
 @Test void bootstrapUsesVerifiedIdentityAndIsIdempotent() throws Exception {
  var f=new BrandFixture();assertThat(f.space()).containsEntry("managementNo",f.space().get("managementNo"));
  assertThatThrownBy(()->f.scopes.create(f.other,"越权","MA1","boot-2")).isInstanceOf(ai.neargo.shop.common.BizException.class);
  assertThatThrownBy(()->f.scopes.create(f.owner,"不同内容","MA1","boot-1")).isInstanceOf(ai.neargo.shop.common.BizException.class);
 }
 @Test void revocationImmediatelyInvalidatesDelegatedAuthority() throws Exception {
  var f=new BrandFixture();var m=f.no(f.space(),"managementNo");
  var root=f.scopes.grant(f.owner,m,new ManagementModels.GrantCommand("MCH:MA3","ENTITY","E1","entity.read",null,null,true,null,false));
  var staff=new ManagementModels.Actor("MAIN","MCH:MA3");
  assertThat(f.scopes.can(staff,m,"ENTITY","E1","entity.read")).isTrue();
  f.scopes.revoke(f.owner,m,f.no(root,"grantNo"),0,"离职");
  assertThat(f.scopes.can(staff,m,"ENTITY","E1","entity.read")).isFalse();
  assertThatThrownBy(()->f.scopes.grant(f.owner,m,new ManagementModels.GrantCommand("MCH:MA3","ENTITY","E1","entity.read",null,null,true,null,false))).isInstanceOf(ai.neargo.shop.common.BizException.class);
 }
 @Test void disabledDelegatorInvalidatesItsChildGrant() throws Exception {
  var f=new BrandFixture();String m=f.no(f.space(),"managementNo");
  for(String permission:new String[]{"management.enter","management.grant.manage"})f.scopes.grant(f.owner,m,new ManagementModels.GrantCommand("MCH:MA3","SPACE",m,permission,null,null,false,null,false));
  f.scopes.grant(f.owner,m,new ManagementModels.GrantCommand("MCH:MA3","ENTITY","E1","entity.read",null,null,true,null,false));
  f.scopes.grant(new ManagementModels.Actor("MAIN","MCH:MA3"),m,new ManagementModels.GrantCommand("MCH:MA2","ENTITY","E1","entity.read",null,null,false,null,false));
  assertThat(f.scopes.can(f.other,m,"ENTITY","E1","entity.read")).isTrue();
  f.jdbc.update("UPDATE mch_account SET status='DISABLED' WHERE mch_account_no='MA3'");
  assertThat(f.scopes.can(f.other,m,"ENTITY","E1","entity.read")).isFalse();
 }

 @Test void tenantAndSubjectKindNeverAlias() throws Exception {
  var f=new BrandFixture();var m=f.no(f.space(),"managementNo");
  assertThat(f.scopes.can(new ManagementModels.Actor("OTHER","MCH:MA1"),m,"SPACE",m,"management.enter")).isFalse();
  assertThat(f.scopes.can(new ManagementModels.Actor("MAIN","USER:MA1"),m,"SPACE",m,"management.enter")).isFalse();
 }
 @Test void bootstrapPersistsDelegatableEntryAndIndependentDiscovery() throws Exception {
  var f=new BrandFixture();String m=f.no(f.space(),"managementNo");
  assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM mch_management_grant WHERE management_no=? AND resource_type='SPACE' AND delegatable=1",Long.class,m)).isEqualTo(6);
  var sources=(java.util.List<java.util.Map<String,Object>>)f.scopes.context(new ManagementModels.Actor("MAIN","USER:U1")).get("bootstrapSources");
  assertThat(sources.getFirst()).containsKey("stores").containsKey("paymentMerchants");
 }
 @Test void delegatedCreatorGetsOnlyNewBrandAuthority() throws Exception {
  var f=new BrandFixture();String m=f.no(f.space(),"managementNo");var staff=new ManagementModels.Actor("MAIN","MCH:MA3");
  for(String permission:new String[]{"management.enter","brand.manage"})f.scopes.grant(f.owner,m,new ManagementModels.GrantCommand(staff.actorKey(),"SPACE",m,permission,null,null,false,null,false));
  var service=new BrandService(f.repo,f.scopes,new ManagementTokenCodec("0123456789abcdefghijklmnopqrstuvwxyz-secret",f.repo.json),(a,e,s)->new BrandChangeImpactPort.Impact(true,false,0,0,0,0),f.numbers());
  var brand=service.create(staff,new ManagementModels.BrandCreate(m,"受托品牌",null,"ACTIVE"),"delegated-create");
  assertThat(f.scopes.can(staff,m,"BRAND",(String)brand.get("brandNo"),"brand.manage")).isTrue();
  assertThat(f.scopes.can(staff,m,"ENTITY","E1","entity.read")).isFalse();
 }
}
