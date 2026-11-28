package ai.neargo.shop.merchant.brand;
import ai.neargo.shop.merchant.brand.impl.BrandServiceImpl;
import ai.neargo.shop.merchant.brand.impl.IssuerAuthorityServiceImpl;
import ai.neargo.shop.merchant.brand.impl.PaymentMerchantScopeServiceImpl;
import ai.neargo.shop.merchant.brand.impl.BrandFinancialActivationServiceImpl;
import ai.neargo.shop.merchant.brand.impl.StoredValueExecutionScopeServiceImpl;
import static ai.neargo.shop.merchant.brand.ManagementModels.*;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.spi.storedvalue.BrandChangeImpactPort;
import ai.neargo.shop.spi.storedvalue.StoredValueBrandActivation.Ack;
import ai.neargo.shop.spi.storedvalue.StoredValueIssuers.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class BrandFinancialActivationTest {
 record F(BrandFixture f,BrandService brands,String management,String brand,BrandFinancialActivationService journal,AuthorizedScope authority,View issuer){}
 F setup()throws Exception{
  var f=new BrandFixture();   // 储值开通不再有开通记录表（G3）：栅栏只在 mch_brand.stored_value_status
  String m=f.no(f.space(),"managementNo");var brands=new BrandServiceImpl(f.repo,f.scopes,new ManagementTokenCodec("test-brand-finance-key-more-than-thirty-two-characters",f.repo.json),(a,e,s)->new BrandChangeImpactPort.Impact(true,false,0,0,0,0),f.numbers());
  String b=f.no(brands.create(f.owner,new BrandCreate(m,"Brand",null,"ACTIVE"),"CREATE"),"brandNo");
  f.scopes.grant(f.owner,m,new GrantCommand(f.owner.actorKey(),"STORE","S1","store.read",null,null,false,null,false));
  f.scopes.grant(f.owner,m,new GrantCommand(f.owner.actorKey(),"STORE","S1","store.brand.change",null,null,false,null,false));
  f.scopes.grant(f.owner,m,new GrantCommand(f.owner.actorKey(),"ENTITY","E1","sv.issuer.settlement.authorize",null,null,false,null,false));
  for(String store:List.of("S1","S2"))f.jdbc.update("INSERT INTO mch_brand_store_binding(tenant_no,binding_no,brand_no,store_no,status,effective_from,scope_revision,change_no,created_at,updated_at,created_by,updated_by) VALUES('MAIN',?,?,?,'ACTIVE',CURRENT_TIMESTAMP,1,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'MCH:MA1','MCH:MA1')","B"+store,b,store,"C"+store);
  f.jdbc.update("UPDATE mch_brand SET scope_revision=2 WHERE brand_no=?",b);
  var routes=List.of(new Route(null,"E1",null,"MERCHANT_OFFLINE","OFFLINE"));var config=new Config("Brand","E1","CENTRALIZED","PRINCIPAL_COMPONENT","PRO_RATA","CNY","Asia/Shanghai",routes,3);
  var authority=new IssuerAuthorityServiceImpl(f.repo,f.scopes,new PaymentMerchantScopeServiceImpl(f.repo,f.scopes)).configureScope(f.owner,m,"BRAND",b,config);
  var issuer=new View("ISSUER","BRAND",b,"Brand","DRAFT","IV1",3,"E1","CNY",routes,"CENTRALIZED","PRINCIPAL_COMPONENT","PRO_RATA","Asia/Shanghai");
  return new F(f,brands,m,b,new BrandFinancialActivationServiceImpl(f.repo,f.scopes,new StoredValueExecutionScopeServiceImpl(f.repo)),authority,issuer);
 }
 @Test void allBrandMembersAreFrozenAndOriginalAcknowledgementDoesNotDependOnTheChangedPayRevision()throws Exception{
  var f=setup();var p=f.journal.prepare(f.f.owner,f.authority,f.issuer,"ACTIVATE");
  assertThat(p.command().stores()).extracting(s->s.entityNo()).containsExactly("E1","E2");
  assertThat(f.f.jdbc.queryForObject("SELECT stored_value_status FROM mch_brand WHERE brand_no=?",String.class,f.brand)).isEqualTo("PREPARING");
  // 准备中的重试：没有冻结的命令可取，按同一修订现算，得到同一个开通号与同一条命令
  assertThat(f.journal.original(f.f.owner,f.management,f.brand,"ACTIVATE",3)).isNull();
  var retry=f.journal.prepare(f.f.owner,f.authority,f.issuer,"ACTIVATE");
  assertThat(retry.activationNo()).isEqualTo(p.activationNo()).hasSize(32).startsWith("BFA");
  assertThat(retry.command()).isEqualTo(p.command());
  assertThatThrownBy(()->f.journal.original(f.f.other,f.management,f.brand,"ACTIVATE",3)).isInstanceOf(BizException.class);
  assertThatThrownBy(()->f.brands.check(f.f.owner,"S1",new ChangeCheck(f.management,null))).isInstanceOf(BizException.class);
  var ack=new Ack(p.activationNo(),"ISSUER","IV1",2,1,"ISSUER_READY");
  assertThat(f.journal.acknowledge(f.f.owner,f.management,f.brand,ack)).containsEntry("state","ACTIVE");
  assertThat(f.journal.original(f.f.owner,f.management,f.brand,"ACTIVATE",3).state()).isEqualTo("ACTIVE");
  assertThat(f.journal.prepare(f.f.owner,f.authority,f.issuer,"ACTIVATE").state()).isEqualTo("ACTIVE");
 }
 @Test void staleSourceAcknowledgementCannotRemoveThePersistentFence()throws Exception{
  var f=setup();var p=f.journal.prepare(f.f.owner,f.authority,f.issuer,"ACTIVATE");
  f.f.jdbc.update("UPDATE mch_brand SET scope_revision=3 WHERE brand_no=?",f.brand);
  assertThatThrownBy(()->f.journal.acknowledge(f.f.owner,f.management,f.brand,new Ack(p.activationNo(),"ISSUER","IV1",2,1,"ISSUER_READY"))).isInstanceOf(BizException.class);
  assertThat(f.f.jdbc.queryForObject("SELECT stored_value_status FROM mch_brand WHERE brand_no=?",String.class,f.brand)).isEqualTo("PREPARING");
  // 回执拼错发行方版本也对不上开通号
  f.f.jdbc.update("UPDATE mch_brand SET scope_revision=2 WHERE brand_no=?",f.brand);
  assertThatThrownBy(()->f.journal.acknowledge(f.f.owner,f.management,f.brand,new Ack(p.activationNo(),"ISSUER","IV2",2,1,"ISSUER_READY"))).isInstanceOf(BizException.class);
 }
}
