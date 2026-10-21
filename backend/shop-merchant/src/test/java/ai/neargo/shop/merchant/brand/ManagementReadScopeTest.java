package ai.neargo.shop.merchant.brand;
import static ai.neargo.shop.merchant.brand.ManagementModels.*;
import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
class ManagementReadScopeTest {
 @Test void brandReaderSeesMemberStoreSummaryButNotEntityFinance() throws Exception {
  var f=new BrandFixture();var m=f.no(f.space(),"managementNo");
  f.jdbc.update("INSERT INTO mch_brand(tenant_no,brand_no,management_no,name,name_key,status,scope_revision,create_key,create_hash,created_at,updated_at,created_by,updated_by) VALUES('MAIN','B1',?,'品牌','品牌','ACTIVE',1,'create','hash',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'MCH:MA1','MCH:MA1')",m);
  f.jdbc.update("INSERT INTO mch_brand_store_binding(tenant_no,binding_no,brand_no,store_no,status,effective_from,scope_revision,change_no,created_at,updated_at,created_by,updated_by) VALUES('MAIN','BIND','B1','S1','ACTIVE',CURRENT_TIMESTAMP,1,'C1',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'MCH:MA1','MCH:MA1')");
  for(var grant:new GrantCommand[]{new GrantCommand("MCH:MA3","SPACE",m,"management.enter",null,null,false,null,false),new GrantCommand("MCH:MA3","BRAND","B1","brand.read",null,null,false,null,false)})f.scopes.grant(f.owner,m,grant);
  var staff=new Actor("MAIN","MCH:MA3");var q=new ManagementQueryService(f.repo,f.scopes,new ManagementTokenCodec("0123456789abcdefghijklmnopqrstuvwxyz-secret",f.repo.json));
  var stores=q.list(staff,m,"STORE","B1",null,null,null,20);
  assertThat(stores.items()).hasSize(1);assertThat(stores.items().getFirst()).containsEntry("name","甲店").doesNotContainKey("entityNo");
  assertThat(q.list(staff,m,"ENTITY",null,null,null,null,20).items()).isEmpty();
 }
 @Test void revokedResourceNeverLeaksThroughAQueryCandidate() throws Exception {
  var f=new BrandFixture();var m=f.no(f.space(),"managementNo");var g=f.scopes.grant(f.owner,m,new GrantCommand("MCH:MA1","ENTITY","E1","entity.read",null,null,false,null,false));
  f.scopes.revoke(f.owner,m,f.no(g,"grantNo"),0,"撤销");
  var q=new ManagementQueryService(f.repo,f.scopes,new ManagementTokenCodec("0123456789abcdefghijklmnopqrstuvwxyz-secret",f.repo.json));assertThat(q.list(f.owner,m,"ENTITY",null,null,null,null,20).items()).isEmpty();
 }
 @Test void grantManagerCannotInspectOrRevokeResourcesItCannotDelegate() throws Exception {
  var f=new BrandFixture();String m=f.no(f.space(),"managementNo");
  var original=f.scopes.grant(f.owner,m,new GrantCommand("MCH:MA1","ENTITY","E1","entity.read",null,null,false,null,false));
  for(String permission:new String[]{"management.enter","management.resource.read","management.grant.manage"})f.scopes.grant(f.owner,m,new GrantCommand("MCH:MA3","SPACE",m,permission,null,null,false,null,false));
  f.scopes.grant(f.owner,m,new GrantCommand("MCH:MA3","ENTITY","E1","entity.read",null,null,false,null,false));
  var staff=new Actor("MAIN","MCH:MA3");var q=new ManagementQueryService(f.repo,f.scopes,new ManagementTokenCodec("0123456789abcdefghijklmnopqrstuvwxyz-secret",f.repo.json));
  assertThat(q.resources(staff,m,null,"GRANTS",null,null,20).items()).noneMatch(row->"ENTITY".equals(row.get("resourceType")));
  assertThatThrownBy(()->f.scopes.revoke(staff,m,f.no(original,"grantNo"),0,"无委派权")).isInstanceOf(ai.neargo.shop.common.BizException.class);
 }
}
