package ai.neargo.shop.merchant.brand;

import ai.neargo.shop.merchant.brand.impl.StoredValuePolicyAuthorityServiceImpl;
import ai.neargo.shop.common.BizException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class StoredValuePolicyAuthorityTest {
 @Test void policyAuthorityDoesNotFollowTenantOrSpaceOwnershipToForeignStore()throws Exception{
  var f=new BrandFixture();String m=f.no(f.space(),"managementNo");var authority=new StoredValuePolicyAuthorityServiceImpl(f.repo,f.scopes);
  assertThat(authority.target(f.owner,m,"SPACE",m,"CNY","sv.policy.manage").targetNo()).isEqualTo(m);
  assertThatThrownBy(()->authority.target(f.owner,m,"STORE","S2","CNY","sv.policy.manage")).isInstanceOf(BizException.class);
  assertThat(authority.catalog(f.owner,m,null,null,"CNY").targets()).noneMatch(t->"S2".equals(t.no()));
 }
 @Test void revokedStorePolicyGrantDisappearsAndActorAliasesHaveSamePrincipal()throws Exception{
  var f=new BrandFixture();String m=f.no(f.space(),"managementNo");var authority=new StoredValuePolicyAuthorityServiceImpl(f.repo,f.scopes);
  var grant=f.scopes.grant(f.owner,m,new ManagementModels.GrantCommand(f.owner.actorKey(),"STORE","S1","sv.policy.read",null,null,false,null,false));
  f.scopes.grant(f.owner,m,new ManagementModels.GrantCommand(f.owner.actorKey(),"STORE","S1","sv.store.activity.manage",null,null,false,null,false));
  assertThat(authority.catalog(f.owner,m,null,null,"CNY").targets()).anyMatch(t->"S1".equals(t.no()));
  assertThat(authority.activity(f.owner,m,"S1","CNY","sv.store.activity.manage").principalNo()).isEqualTo("USER:U1");
  f.scopes.revoke(f.owner,m,f.no(grant,"grantNo"),0,"Revoked");
  assertThat(authority.catalog(f.owner,m,null,null,"CNY").targets()).noneMatch(t->"S1".equals(t.no()));
  assertThatThrownBy(()->authority.target(f.owner,m,"STORE","S1","CNY","sv.policy.read")).isInstanceOf(BizException.class);
 }
 @Test void preparingDelegationOptionsOnlyMaterializesAlreadyOwnedAuthority()throws Exception{
  var f=new BrandFixture();String m=f.no(f.space(),"managementNo");var authority=new StoredValuePolicyAuthorityServiceImpl(f.repo,f.scopes);
  f.jdbc.update("DELETE FROM mch_management_grant WHERE management_no=? AND permission_code IN ('sv.policy.read','sv.policy.manage','sv.store.activity.approve')",m);
  authority.prepareOwnPermissions(f.owner,m);authority.prepareOwnPermissions(f.owner,m);
  assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM mch_management_grant WHERE management_no=? AND resource_type='SPACE' AND permission_code IN ('sv.policy.read','sv.policy.manage','sv.store.activity.approve')",Long.class,m)).isEqualTo(3);
  assertThat(f.scopes.can(f.owner,m,"STORE","S2","sv.policy.manage")).isFalse();assertThatThrownBy(()->authority.prepareOwnPermissions(f.other,m)).isInstanceOf(BizException.class);
 }
 @Test void policyTargetsUsePolicyAuthorityWithoutOrganizationRead()throws Exception{
  var f=new BrandFixture();String m=f.no(f.space(),"managementNo");var actor=new ManagementModels.Actor("MAIN","MCH:MA3");var authority=new StoredValuePolicyAuthorityServiceImpl(f.repo,f.scopes);
  f.scopes.grant(f.owner,m,new ManagementModels.GrantCommand(actor.actorKey(),"SPACE",m,"management.enter",null,null,false,null,false));
  f.scopes.grant(f.owner,m,new ManagementModels.GrantCommand(actor.actorKey(),"STORE","S1","sv.policy.manage",null,null,false,null,false));
  assertThat(f.scopes.can(actor,m,"STORE","S1","store.read")).isFalse();
  assertThat(authority.policyTargets(actor,m)).singleElement().satisfies(t->{assertThat(t.no()).isEqualTo("S1");assertThat(t.canManage()).isTrue();});
 }
 @Test void activityReadContextDoesNotGrantMutationPermission()throws Exception{
  var f=new BrandFixture();String m=f.no(f.space(),"managementNo");var actor=new ManagementModels.Actor("MAIN","MCH:MA3");var authority=new StoredValuePolicyAuthorityServiceImpl(f.repo,f.scopes);
  f.scopes.grant(f.owner,m,new ManagementModels.GrantCommand(actor.actorKey(),"SPACE",m,"management.enter",null,null,false,null,false));
  f.scopes.grant(f.owner,m,new ManagementModels.GrantCommand(actor.actorKey(),"STORE","S1","sv.store.activity.read",null,null,false,null,false));
  assertThat(authority.activity(actor,m,"S1","CNY","sv.store.activity.read").policyScope().storeNo()).isEqualTo("S1");assertThat(authority.canActivity(actor,m,"S1","sv.store.activity.manage")).isFalse();assertThat(authority.canActivity(actor,m,"S1","sv.store.activity.publish")).isFalse();
 }
}
