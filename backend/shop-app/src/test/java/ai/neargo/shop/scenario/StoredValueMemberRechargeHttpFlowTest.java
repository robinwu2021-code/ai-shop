package ai.neargo.shop.scenario;

import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.TokenStores;
import ai.neargo.shop.common.OtpStore;
import ai.neargo.shop.support.TestLogin;
import ai.neargo.shop.pay.storedvalue.service.StoredValuePlanService;
import ai.neargo.shop.pay.storedvalue.service.StoredValuePlanRechargeService;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.storedvalue.StoredValuePlans;
import java.util.Map;
import java.util.UUID;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest(properties={"shop.member-code.enabled=true","shop.pay.stored-value.enabled=true",
 "shop.pay.entitlement.cursor-key=member-recharge-http-cursor-key-at-least-thirty-two-characters",
 "spring.datasource.url=jdbc:h2:mem:member-recharge-http;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@ActiveProfiles("test")
class StoredValueMemberRechargeHttpFlowTest {
 @Autowired WebApplicationContext web;@Autowired ObjectMapper json;@Autowired JdbcTemplate jdbc;
 @Autowired TokenStores tokens;@Autowired OtpStore otp;@Autowired StoredValuePlanService plans;
 @Autowired StoredValuePlanRechargeService direct;
 @Test void enrollSelectConfirmReplayAndReadUnregisteredCustomerWithoutAPlatformLogin() throws Exception {
  var mvc=MockMvcBuilders.webAppContextSetup(web).apply(SecurityMockMvcConfigurers.springSecurity()).build();
  String ownerPhone=phone(),customerPhone=phone();String buyer=TestLogin.consumer(mvc,json,otp,ownerPhone);
  String owner=tokens.of(Realm.CONSUMER).get(buyer).orElseThrow().user().userNo();
  String entity=id(),store=id();
  jdbc.update("INSERT INTO mch_entity(entity_no,owner_user_no,name,status,market,tenant_no,created_at,updated_at) VALUES (?,?,'beauty','ACTIVE','CN','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",entity,owner);
  jdbc.update("INSERT INTO mch_store(store_no,entity_no,name,status,tenant_no,created_at,updated_at) VALUES (?,?,'beauty store','ACTIVE','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",store,entity);
  jdbc.update("INSERT INTO mch_account(mch_account_no,entity_no,user_no,is_owner,is_primary,status,tenant_no,created_at,updated_at) VALUES (?,?,?,1,1,'ACTIVE','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",id(),entity,owner);
  String merchant=TestLogin.merchantOwner(mvc,json,otp,ownerPhone);
  var enrolled=send(mvc,"/biz/members",merchant,store,Map.of("phone",customerPhone,"remark","林女士","storeNo",store),id());
  assertThat(enrolled.path("code").asInt()).isZero();String member=enrolled.path("data").path("member").path("memberNo").asString();
  var candidates=read(mvc,"/biz/stored-value/customer-candidates?storeNo="+store+"&phone="+customerPhone,merchant,store);
  assertThat(candidates.path("code").asInt()).isZero();var candidate=candidates.path("data").path("records").get(0);
  assertThat(candidate.path("canRecharge").asBoolean()).isTrue();assertThat(candidate.path("account").isNull()).isTrue();
  if(member.isBlank())member=candidate.path("memberNo").asString();
  var selection=send(mvc,"/biz/stored-value/customer-selections",merchant,store,Map.of("storeNo",store,"source",Map.of("type","MEMBER","memberNo",member)),id());
  assertThat(selection.path("code").asInt()).isZero();String ref=selection.path("data").path("customerRef").asString();
  var plan=plans.create(new CatalogScope("MAIN",entity,owner,false),new StoredValuePlans.Create(store,"充100送30","CNY",10000,3000),id());
  var body=Map.of("storeNo",store,"customerRef",ref,"planNo",plan.planNo(),"versionNo",plan.versionNo());String key=id();
  assertThat(send(mvc,"/biz/stored-value/plan-recharges/confirm-offline",buyer,store,body,id()).path("code").asInt()).isNotZero();
  var first=send(mvc,"/biz/stored-value/plan-recharges/confirm-offline",merchant,store,body,key);
  assertThat(first.path("code").asInt()).isZero();String no=first.path("data").path("rechargeNo").asString();
  String person=jdbc.queryForObject("SELECT person_no FROM mbr_member WHERE member_no=?",String.class,member);
  jdbc.update("UPDATE mbr_identity_ticket SET issued_at=?,expires_at=? WHERE customer_no=?",LocalDateTime.now(ZoneOffset.UTC).minusMinutes(2),LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1),person);
  var replay=send(mvc,"/biz/stored-value/plan-recharges/confirm-offline",merchant,store,body,key);
  assertThat(replay.path("code").asInt()).isZero();assertThat(replay.path("data").path("rechargeNo").asString()).isEqualTo(no);
  direct.recover(new EntitlementContext("MAIN",owner,id(),"0".repeat(64)),entity,no);
  var directory=read(mvc,"/biz/stored-value/account-directory?storeNo="+store+"&keyword="+customerPhone,merchant,store);
  assertThat(directory.path("code").asInt()).isZero();assertThat(directory.path("data").path("records").size()).isEqualTo(1);
  String account=directory.path("data").path("records").get(0).path("accountRef").asString();
  var continuation=send(mvc,"/biz/stored-value/customer-selections",merchant,store,Map.of("storeNo",store,"source",Map.of("type","ACCOUNT","accountRef",account)),id());
  assertThat(continuation.path("code").asInt()).isZero();assertThat(continuation.path("data").path("account").path("availableMinor").asLong()).isEqualTo(13000);
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM usr_account WHERE phone=?",Integer.class,customerPhone)).isZero();
 }
 private JsonNode send(MockMvc mvc,String path,String token,String store,Object body,String key)throws Exception{
  return json.readTree(mvc.perform(post(path).header("Authorization","Bearer "+token).header("X-Store-No",store).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andReturn().getResponse().getContentAsString());
 }
 private JsonNode read(MockMvc mvc,String path,String token,String store)throws Exception{
  return json.readTree(mvc.perform(get(path).header("Authorization","Bearer "+token).header("X-Store-No",store)).andReturn().getResponse().getContentAsString());
 }
 private static String id(){return UUID.randomUUID().toString();}
 private static String phone(){return "139"+String.format("%08d",Math.floorMod(UUID.randomUUID().getLeastSignificantBits(),100000000));}
}
