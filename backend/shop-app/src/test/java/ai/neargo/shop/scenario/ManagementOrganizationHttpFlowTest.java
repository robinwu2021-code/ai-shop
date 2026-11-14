package ai.neargo.shop.scenario;
import ai.neargo.shop.auth.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.http.MediaType;
import tools.jackson.databind.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.assertj.core.api.Assertions.*;
import java.util.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:management-http;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "shop.management.signing-key=management-http-test-key-at-least-thirty-two-characters"})
@ActiveProfiles("test")
class ManagementOrganizationHttpFlowTest {
 @Autowired WebApplicationContext web;@Autowired JdbcTemplate jdbc;@Autowired ObjectMapper json;@Autowired TokenStores tokens;
 @Test void managementBootstrapBrandAndStoreChangeUseRealSecurityAndDurableCommands() throws Exception {
  var mvc=MockMvcBuilders.webAppContextSetup(web).apply(SecurityMockMvcConfigurers.springSecurity()).build();
  String suffix=UUID.randomUUID().toString().replace("-","");String entity="ME"+suffix,store="MS"+suffix,account="MA"+suffix;
  jdbc.update("INSERT INTO mch_entity(entity_no,name,status,market,tenant_no,created_at,updated_at) VALUES (?,'组织联调主体','ACTIVE','CN','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",entity);
  jdbc.update("INSERT INTO mch_store(store_no,entity_no,name,status,tenant_no,created_at,updated_at) VALUES (?,?,'组织联调门店','ACTIVE','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",store,entity);
  jdbc.update("INSERT INTO mch_account(mch_account_no,entity_no,login_phone,display_name,is_owner,is_primary,status,tenant_no,created_at,updated_at) VALUES (?,?,'13912345678','负责人',1,1,'ACTIVE','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",account,entity);
  String token=tokens.of(Realm.MERCHANT).issue(TokenStore.SessionData.of(LoginUser.merchant(account,"负责人")));
  var response=mvc.perform(post("/biz/management/spaces").header("Authorization","Bearer "+token).header("Idempotency-Key","create-space").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("name","组织联调","sourceAccountNo",account)))).andReturn().getResponse();
  var space=json.readTree(response.getContentAsString());assertThat(space.path("code").asInt()).as(response.getContentAsString()).isZero();String m=space.path("data").path("managementNo").asString();
  var created=mvc.perform(post("/biz/management/brands").header("Authorization","Bearer "+token).header("Idempotency-Key","create-brand").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("managementNo",m,"name","品牌联调","status","ACTIVE")))).andReturn().getResponse();
  var brand=json.readTree(created.getContentAsString());assertThat(brand.path("code").asInt()).as(created.getContentAsString()).isZero();String b=brand.path("data").path("brandNo").asString();
  var replay=mvc.perform(post("/biz/management/brands").header("Authorization","Bearer "+token).header("Idempotency-Key","create-brand").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("managementNo",m,"name","品牌联调","status","ACTIVE")))).andReturn().getResponse();
  assertThat(json.readTree(replay.getContentAsString()).path("data").path("brandNo").asString()).isEqualTo(b);
  var audit=mvc.perform(get("/biz/management/brands/"+b+"/audit-events").header("Authorization","Bearer "+token)).andReturn().getResponse();
  assertThat(json.readTree(audit.getContentAsString()).path("data").path("items").size()).isPositive();
  var denied=mvc.perform(get("/biz/management/entities/"+entity+"/payment-merchants").param("managementNo",m).header("Authorization","Bearer "+token)).andReturn().getResponse();assertThat(json.readTree(denied.getContentAsString()).path("code").asInt()).isNotZero();
  for(String permission:List.of("store.read","store.brand.change")){
   var grant=mvc.perform(post("/biz/management/spaces/"+m+"/grants").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("actorNo","MCH:"+account,"resourceType","STORE","resourceNo",store,"permissionCode",permission)))).andReturn().getResponse();assertThat(json.readTree(grant.getContentAsString()).path("code").asInt()).as(grant.getContentAsString()).isZero();
  }
  var checked=mvc.perform(post("/biz/stores/"+store+"/brand-changes/check").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("managementNo",m,"toBrandNo",b)))).andReturn().getResponse();
  var check=json.readTree(checked.getContentAsString());assertThat(check.path("code").asInt()).as(checked.getContentAsString()).isZero();assertThat(check.path("data").path("allowed").asBoolean()).isTrue();
  var body=new LinkedHashMap<String,Object>();body.put("managementNo",m);body.put("toBrandNo",b);body.put("checkToken",check.path("data").path("checkToken").asString());body.put("expectedStoreRevision",check.path("data").path("storeVersion").asLong());body.put("expectedScopeRevision",0);body.put("expectedFromBrandRevision",null);body.put("expectedToBrandRevision",0);body.put("reason","组织联调");
  var submitted=mvc.perform(post("/biz/stores/"+store+"/brand-changes").header("Authorization","Bearer "+token).header("Idempotency-Key","bind-store").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andReturn().getResponse();
  var change=json.readTree(submitted.getContentAsString());assertThat(change.path("code").asInt()).as(submitted.getContentAsString()).isZero();assertThat(change.path("data").path("status").asString()).isEqualTo("CHECKED");String changeNo=change.path("data").path("changeNo").asString();
  var applied=mvc.perform(post("/biz/management/brand-changes/"+changeNo+"/decisions").header("Authorization","Bearer "+token).header("Idempotency-Key","approve-bind").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("decision","APPROVE","expectedRevision",0,"reason","确认组织关联")))).andReturn().getResponse();
  assertThat(json.readTree(applied.getContentAsString()).path("data").path("status").asString()).as(applied.getContentAsString()).isEqualTo("COMPLETED");
  assertThat(jdbc.queryForObject("SELECT entity_no FROM mch_store WHERE store_no=?",String.class,store)).isEqualTo(entity);
  var auditAfter=mvc.perform(get("/biz/management/brands/"+b+"/audit-events").header("Authorization","Bearer "+token)).andReturn().getResponse();assertThat(json.readTree(auditAfter.getContentAsString()).path("data").path("items").toString()).contains("BRAND_STORE_CHANGED");
  String consumer=tokens.of(Realm.CONSUMER).issue(TokenStore.SessionData.of(LoginUser.consumer(account,"消费者")));
  var wrongRealm=mvc.perform(get("/biz/management/context").header("Authorization","Bearer "+consumer)).andReturn().getResponse();assertThat(wrongRealm.getStatus()==401||json.readTree(wrongRealm.getContentAsString()).path("code").asInt()!=0).isTrue();
 }
}
