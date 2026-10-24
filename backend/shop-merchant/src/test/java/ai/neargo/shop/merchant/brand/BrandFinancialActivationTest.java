package ai.neargo.shop.merchant.brand;
import static ai.neargo.shop.merchant.brand.ManagementModels.*;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.spi.storedvalue.StoredValueBrandActivation.Ack;
import ai.neargo.shop.spi.storedvalue.StoredValueIssuers.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class BrandFinancialActivationTest {
 record F(BrandFixture f,BrandService brands,String management,String brand,BrandFinancialActivationService journal,AuthorizedScope authority,View issuer){}
 F setup()throws Exception{
  var f=new BrandFixture();String sql=Files.readString(Path.of("../shop-app/src/main/resources/db/migration/V573__brand_financial_activation_fence.sql")).replaceAll("(?m)^--.*$","").replaceAll(" ENGINE=InnoDB[^;]*","").replace(" JSON"," VARCHAR(30000)").replace("status IN ('PREPARING','ACTIVE')","CASE status WHEN 'PREPARING' THEN TRUE WHEN 'ACTIVE' THEN TRUE ELSE FALSE END");
  for(String part:sql.split(";"))if(!part.isBlank())f.jdbc.execute(part);
  String m=f.no(f.space(),"managementNo");var brands=new BrandService(f.repo,f.scopes,new ManagementTokenCodec("test-brand-finance-key-more-than-thirty-two-characters",f.repo.json),(a,e,s)->new BrandChangeImpactPort.Impact(true,false,0,0,0,0),f.numbers());
  String b=f.no(brands.create(f.owner,new BrandCreate(m,"Brand",null,"ACTIVE"),"CREATE"),"brandNo");
  f.scopes.grant(f.owner,m,new GrantCommand(f.owner.actorKey(),"STORE","S1","store.read",null,null,false,null,false));
  f.scopes.grant(f.owner,m,new GrantCommand(f.owner.actorKey(),"STORE","S1","store.brand.change",null,null,false,null,false));
  f.scopes.grant(f.owner,m,new GrantCommand(f.owner.actorKey(),"ENTITY","E1","sv.issuer.settlement.authorize",null,null,false,null,false));
  for(String store:List.of("S1","S2"))f.jdbc.update("INSERT INTO mch_brand_store_binding(tenant_no,binding_no,brand_no,store_no,status,effective_from,scope_revision,change_no,created_at,updated_at,created_by,updated_by) VALUES('MAIN',?,?,?,'ACTIVE',CURRENT_TIMESTAMP,1,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'MCH:MA1','MCH:MA1')","B"+store,b,store,"C"+store);
  f.jdbc.update("UPDATE mch_brand SET scope_revision=2 WHERE brand_no=?",b);
  var routes=List.of(new Route(null,"E1",null,"MERCHANT_OFFLINE","OFFLINE"));var config=new Config("Brand","E1","CENTRALIZED","PRINCIPAL_COMPONENT","PRO_RATA","CNY","Asia/Shanghai",routes,3);
  var authority=new IssuerAuthorityService(f.repo,f.scopes,new PaymentMerchantScopeService(f.repo,f.scopes)).configureScope(f.owner,m,"BRAND",b,config);
  var issuer=new View("ISSUER","BRAND",b,"Brand","DRAFT","IV1",3,"E1","CNY",routes,"CENTRALIZED","PRINCIPAL_COMPONENT","PRO_RATA","Asia/Shanghai");
  return new F(f,brands,m,b,new BrandFinancialActivationService(f.repo,f.scopes,new StoredValueExecutionScopeService(f.repo)),authority,issuer);
 }
 @Test void allBrandMembersAreFrozenAndOriginalAcknowledgementDoesNotDependOnTheChangedPayRevision()throws Exception{
  var f=setup();var p=f.journal.prepare(f.f.owner,f.authority,f.issuer,"ACTIVATE");
  assertThat(p.command().stores()).extracting(s->s.entityNo()).containsExactly("E1","E2");
  assertThat(f.f.jdbc.queryForObject("SELECT stored_value_status FROM mch_brand WHERE brand_no=?",String.class,f.brand)).isEqualTo("PREPARING");
  assertThat(f.journal.original(f.f.owner,f.management,f.brand,"ACTIVATE",3).command()).isEqualTo(p.command());
  assertThatThrownBy(()->f.journal.original(f.f.owner,f.management,f.brand,"OTHER",3)).isInstanceOf(BizException.class);
  assertThatThrownBy(()->f.journal.original(f.f.other,f.management,f.brand,"ACTIVATE",3)).isInstanceOf(BizException.class);
  assertThatThrownBy(()->f.brands.check(f.f.owner,"S1",new ChangeCheck(f.management,null))).isInstanceOf(BizException.class);
  var ack=new Ack(p.activationNo(),"ISSUER","IV1",2,1,"ISSUER_READY");
  assertThat(f.journal.acknowledge(f.f.owner,f.management,f.brand,ack)).containsEntry("state","ACTIVE");
  assertThat(f.journal.original(f.f.owner,f.management,f.brand,"ACTIVATE",3).state()).isEqualTo("ACTIVE");
  assertThat(f.f.jdbc.queryForObject("SELECT COUNT(*) FROM mch_brand_financial_activation",Long.class)).isEqualTo(1);
 }
 @Test void staleSourceAcknowledgementCannotRemoveThePersistentFence()throws Exception{
  var f=setup();var p=f.journal.prepare(f.f.owner,f.authority,f.issuer,"ACTIVATE");
  f.f.jdbc.update("UPDATE mch_brand SET scope_revision=3 WHERE brand_no=?",f.brand);
  assertThatThrownBy(()->f.journal.acknowledge(f.f.owner,f.management,f.brand,new Ack(p.activationNo(),"ISSUER","IV1",2,1,"ISSUER_READY"))).isInstanceOf(BizException.class);
  assertThat(f.f.jdbc.queryForObject("SELECT stored_value_status FROM mch_brand WHERE brand_no=?",String.class,f.brand)).isEqualTo("PREPARING");
  assertThat(f.f.jdbc.queryForObject("SELECT status FROM mch_brand_financial_activation",String.class)).isEqualTo("PREPARING");
 }
}
