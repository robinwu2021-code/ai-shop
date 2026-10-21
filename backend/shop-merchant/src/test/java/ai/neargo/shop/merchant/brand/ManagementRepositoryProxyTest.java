package ai.neargo.shop.merchant.brand;
import static ai.neargo.shop.merchant.brand.ManagementModels.*;
import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
class ManagementRepositoryProxyTest {
 private ManagementRepository proxied(BrandFixture f){var factory=new ProxyFactory(f.repo);factory.setProxyTargetClass(true);factory.addAdvice((org.aopalliance.intercept.MethodInterceptor)call->call.proceed());return (ManagementRepository)factory.getProxy();}
 @Test void brandDetailWorksWithRepositoryExceptionTranslationProxy() throws Exception {
  var f=new BrandFixture();String m=f.no(f.space(),"managementNo");var repo=proxied(f);
  var service=new BrandService(repo,f.scopes,new ManagementTokenCodec("0123456789abcdefghijklmnopqrstuvwxyz-secret",f.repo.json),(a,e,s)->new BrandChangeImpactPort.Impact(true,false,0,0,0,0),f.numbers());
  String b=f.no(service.create(f.owner,new BrandCreate(m,"代理验证品牌",null,"ACTIVE"),"proxy-brand"),"brandNo");
  assertThat(service.detail(f.owner,b)).containsEntry("storeCount",0L).containsKey("actions");
 }
 @Test void paymentMethodsAreParsedThroughRepositoryProxy() throws Exception {
  var f=new BrandFixture();String m=f.no(f.space(),"managementNo");
  f.jdbc.update("INSERT INTO mch_payment_merchant VALUES('MAIN','PM1','E1','','WECHAT','ACTIVE','[\"JSAPI\"]',0)");
  for(String p:new String[]{"entity.read","payment.merchant.read"})f.scopes.grant(f.owner,m,new GrantCommand("MCH:MA1","ENTITY","E1",p,null,null,false,null,false));
  f.scopes.grant(f.owner,m,new GrantCommand("MCH:MA1","PAYMENT_MERCHANT","PM1","payment.merchant.read",null,null,false,null,false));
  assertThat(new PaymentMerchantScopeService(proxied(f),f.scopes).candidates(f.owner,m,"E1","WECHAT","JSAPI",null)).extracting(x->x.get("payMerchantNo")).containsExactly("PM1");
 }
}
