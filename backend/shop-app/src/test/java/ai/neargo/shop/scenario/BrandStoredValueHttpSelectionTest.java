package ai.neargo.shop.scenario;
import ai.neargo.shop.auth.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.assertj.core.api.Assertions.*;
/** Public boundary uses real auth, organization lookup, pricing, consent and Pay ledger in an isolated DB. */
class BrandStoredValueHttpSelectionTest extends BrandStoredValueRedemptionTest {
 @Autowired WebApplicationContext web;@Autowired TokenStores tokens;
 JsonNode send(MockMvc mvc,String path,String token,String store,Object body,String key)throws Exception{
  return json.readTree(mvc.perform(post(path).header("Authorization","Bearer "+token).header("X-Store-No",store).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andReturn().getResponse().getContentAsString());
 }
 @Test void selectedOriginalIsOwnedAndExactAndConsumerConsentCannotBeReplaced()throws Exception{
  var f=fixture(1000000,id());recharges.confirm(f.ctx(),f.entity(),f.create());
  jdbc.update("INSERT INTO mch_account(mch_account_no,entity_no,is_owner,is_primary,status,tenant_no,created_at,updated_at) VALUES(?,?,1,1,'ACTIVE','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",f.actor(),f.entity());
  String m=f.qb().scope().managementNo();
  jdbc.update("INSERT INTO mch_management_space(tenant_no,management_no,name,owner_actor_no,status,bootstrap_source_account_no,bootstrap_key,bootstrap_hash,created_at,updated_at,created_by,updated_by) VALUES('MAIN',?,'Test',?,'ACTIVE',?,'K',?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,?,?)",m,f.actor(),f.actor(),"a".repeat(64),f.actor(),f.actor());
  jdbc.update("INSERT INTO mch_brand(tenant_no,brand_no,management_no,name,status,scope_revision,name_key,create_key,create_hash,created_at,updated_at,created_by,updated_by) VALUES('MAIN',?,?,'Test','ACTIVE',0,'Test','K',?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,?,?)",f.brand(),m,"a".repeat(64),f.actor(),f.actor());
  jdbc.update("INSERT INTO mch_brand_store_binding(tenant_no,binding_no,brand_no,store_no,status,effective_from,scope_revision,change_no,created_at,updated_at,created_by,updated_by) VALUES('MAIN',?,?,?,'ACTIVE',CURRENT_TIMESTAMP,0,'K',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,?,?)","B"+f.b(),f.brand(),f.b(),f.actor(),f.actor());
  String good=id(),sku=id();jdbc.update("INSERT INTO prd_goods(goods_no,entity_no,store_no,title,type,on_sale,audit_status,fulfillments,tenant_no,created_at,updated_at) VALUES(?,?,?,'Care','SERVICE',1,'APPROVED','[\"STORE_VERIFY\"]','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",good,f.entity(),f.b());
  jdbc.update("INSERT INTO prd_sku(sku_no,goods_no,entity_no,market,price,stock,locked_stock,tenant_no,created_at,updated_at) VALUES(?,?,?,'CN',5000,0,0,'MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",sku,good,f.entity());
  var mvc=MockMvcBuilders.webAppContextSetup(web).apply(SecurityMockMvcConfigurers.springSecurity()).build();
  String b=tokens.of(Realm.MERCHANT).issue(TokenStore.SessionData.of(LoginUser.merchant(f.actor(),"Owner")));
  String c=tokens.of(Realm.CONSUMER).issue(TokenStore.SessionData.of(LoginUser.consumer(f.user(),"Customer")));
  var wallet=wallets.accounts(f.qb()).getFirst();
  String ref=codes.resolve(new ai.neargo.shop.member.dto.MemberCodeScope("MAIN",f.entity(),f.b(),f.actor()),codes.issue("MAIN",f.user()).memberCode()).customerRef();
  var body=new HashMap<String,Object>(Map.of("storeNo",f.b(),"customerRef",ref,"issuerRef",wallet.issuerRef(),"accountRef",wallet.accountRef(),"items",List.of(Map.of("skuNo",sku,"quantity",1))));String key=id();
  var reply=send(mvc,"/biz/stored-value/consumptions/preview",b,f.b(),body,key);assertThat(reply.path("code").asInt()).as(reply.toString()).isZero();
  var service=reply.path("data").path("service");String order=service.path("serviceOrderNo").asString();var confirm=service.path("confirmation");
  var command=Map.of("orderNo",order,"orderRevision",confirm.path("orderRevision").asLong(),"customerRef",ref);
  assertThat(send(mvc,"/biz/stored-value/consumptions",b,f.b(),command,id()).path("code").asInt()).isNotZero();assertThat(balance(f.issuer())).isEqualTo(13000);
  var tampered=new HashMap<>(body);tampered.put("accountRef","OTHER");assertThat(send(mvc,"/biz/stored-value/consumptions/resolve",b,f.b(),tampered,key).path("code").asInt()).isNotZero();
  var badConsent=Map.of("orderRevision",confirm.path("orderRevision").asLong(),"quoteRef",confirm.path("quoteRef").asString(),"selectionDigest","0".repeat(64),"requestKey",id());
  assertThat(send(mvc,"/mp/orders/"+order+"/entitlement-authorizations",c,f.b(),badConsent,id()).path("code").asInt()).isNotZero();
  var consent=new HashMap<String,Object>(badConsent);consent.put("selectionDigest",confirm.path("selectionDigest").asString());
  var authorized=send(mvc,"/mp/orders/"+order+"/entitlement-authorizations",c,f.b(),consent,id());assertThat(authorized.path("code").asInt()).as(authorized.toString()).isZero();
  var used=send(mvc,"/biz/stored-value/consumptions",b,f.b(),command,id());assertThat(used.path("code").asInt()).as(used.toString()).isZero();assertThat(balance(f.issuer())).isEqualTo(8000);
  jdbc.update("UPDATE mbr_identity_ticket SET expires_at=DATEADD('SECOND',-1,CURRENT_TIMESTAMP),issued_at=DATEADD('MINUTE',-2,CURRENT_TIMESTAMP) WHERE user_no=?",f.user());
  var found=send(mvc,"/biz/stored-value/consumptions/resolve",b,f.b(),body,key);assertThat(found.path("code").asInt()).as(found.toString()).isZero();assertThat(found.path("data").path("found").asBoolean()).isTrue();
  assertThat(send(mvc,"/biz/stored-value/consumptions",b,f.b(),command,id()).path("code").asInt()).isZero();assertThat(balance(f.issuer())).isEqualTo(8000);
  assertThat(send(mvc,"/biz/stored-value/consumptions/resolve",c,f.b(),body,key).path("code").asInt()).isNotZero();
 }
}
