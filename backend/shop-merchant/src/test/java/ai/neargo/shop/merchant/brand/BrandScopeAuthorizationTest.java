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
 @Test void tenantAndSubjectKindNeverAlias() throws Exception {
  var f=new BrandFixture();var m=f.no(f.space(),"managementNo");
  assertThat(f.scopes.can(new ManagementModels.Actor("OTHER","MCH:MA1"),m,"SPACE",m,"management.enter")).isFalse();
  assertThat(f.scopes.can(new ManagementModels.Actor("MAIN","USER:MA1"),m,"SPACE",m,"management.enter")).isFalse();
 }
}
