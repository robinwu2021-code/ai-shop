package ai.neargo.shop.merchant.brand;
import static ai.neargo.shop.merchant.brand.ManagementModels.*;
import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
class PaymentMerchantOwnershipTest {
 @Test void entityChannelStatusAndSpecificResourceAuthorityAreAllRequired() throws Exception {
  var f=new BrandFixture();var m=f.no(f.space(),"managementNo");
  f.jdbc.update("INSERT INTO mch_payment_merchant VALUES('MAIN','PM1','E1','','WECHAT','ACTIVE','[\"JSAPI\"]',0),('MAIN','PM2','E2','','WECHAT','ACTIVE','[\"JSAPI\"]',0),('MAIN','PM3','E1','','ALIPAY','FROZEN','[\"APP\"]',0)");
  f.scopes.grant(f.owner,m,new GrantCommand("MCH:MA1","ENTITY","E1","entity.read",null,null,false,null,false));
  f.scopes.grant(f.owner,m,new GrantCommand("MCH:MA1","ENTITY","E1","payment.merchant.read",null,null,false,null,false));
  f.scopes.grant(f.owner,m,new GrantCommand("MCH:MA1","PAYMENT_MERCHANT","PM1","payment.merchant.read",null,null,false,null,false));
  var service=new PaymentMerchantScopeService(f.repo,f.scopes);
  assertThat(service.candidates(f.owner,m,"E1","WECHAT","JSAPI",null)).extracting(x->x.get("payMerchantNo")).containsExactly("PM1");
  assertThat(service.candidates(f.owner,m,"E1","WECHAT","APP",null)).isEmpty();
  assertThat(service.candidates(f.owner,m,"E1","ALIPAY",null,null)).isEmpty();
  assertThatThrownBy(()->service.candidates(f.owner,m,"E2",null,null,null)).isInstanceOf(ai.neargo.shop.common.BizException.class);
 }
 @Test void storeScopeDoesNotSelectAnotherStoresDedicatedMerchant() throws Exception {
  var f=new BrandFixture();var m=f.no(f.space(),"managementNo");
  f.jdbc.update("INSERT INTO mch_payment_merchant VALUES('MAIN','PM1','E1','S-other','WECHAT','ACTIVE','[\"JSAPI\"]',0)");
  for(String p:new String[]{"entity.read","payment.merchant.read"})f.scopes.grant(f.owner,m,new GrantCommand("MCH:MA1","ENTITY","E1",p,null,null,false,null,false));
  f.scopes.grant(f.owner,m,new GrantCommand("MCH:MA1","PAYMENT_MERCHANT","PM1","payment.merchant.read",null,null,false,null,false));
  f.scopes.grant(f.owner,m,new GrantCommand("MCH:MA1","STORE","S1","store.read",null,null,false,null,false));
  assertThat(new PaymentMerchantScopeService(f.repo,f.scopes).candidates(f.owner,m,"E1",null,null,"S1")).isEmpty();
 }
}
