package ai.neargo.shop.scenario;

import ai.neargo.shop.auth.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:issuer-http;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","shop.management.signing-key=issuer-http-test-key-at-least-thirty-two-characters"})
@ActiveProfiles("test")
class IssuerConfigurationHttpFlowTest {
    @Autowired WebApplicationContext web;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired TokenStores tokens;

    @Test void brandIssuerRequiresSettlementGrantAndNeverCreatesAnAccount() throws Exception {
        // Use the generated final schema; replaying V548/V549 duplicates existing issuer tables.
        var mvc=MockMvcBuilders.webAppContextSetup(web).apply(SecurityMockMvcConfigurers.springSecurity()).build();
        String entity="E1",account="MA1";
        jdbc.update("INSERT INTO mch_entity(entity_no,name,status,market,tenant_no,created_at,updated_at) VALUES (?,'发行测试主体','ACTIVE','CN','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",entity);
        jdbc.update("INSERT INTO mch_account(mch_account_no,entity_no,is_owner,is_primary,status,tenant_no,created_at,updated_at) VALUES (?,?,1,1,'ACTIVE','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",account,entity);
        String token=tokens.of(Realm.MERCHANT).issue(TokenStore.SessionData.of(LoginUser.merchant(account,"发行测试")));
        var created=mvc.perform(post("/biz/management/spaces").header("Authorization","Bearer "+token).header("Idempotency-Key","M1").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("name","发行测试","sourceAccountNo",account)))).andReturn().getResponse();
        var space=json.readTree(created.getContentAsString());assertThat(space.path("code").asInt()).as(created.getContentAsString()).isZero();String m=space.path("data").path("managementNo").asString();
        var brandResponse=mvc.perform(post("/biz/management/brands").header("Authorization","Bearer "+token).header("Idempotency-Key","B1").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("managementNo",m,"name","发行品牌","status","DRAFT")))).andReturn().getResponse();
        var brand=json.readTree(brandResponse.getContentAsString());assertThat(brand.path("code").asInt()).as(brandResponse.getContentAsString()).isZero();String b=brand.path("data").path("brandNo").asString();
        var route=new LinkedHashMap<String,Object>();route.put("paymentMerchantNo",null);route.put("entityNo",entity);route.put("storeNo",null);route.put("channel","MERCHANT_OFFLINE");route.put("scene","OFFLINE");
        var config=Map.of("name","品牌发行","settlementEntityNo",entity,"collectionMode","CENTRALIZED","clearingMode","INTERNAL_RECON","debitAllocationMode","PRO_RATA","currency","CNY","marketZone","Asia/Shanghai","routes",List.of(route),"expectedRevision",0);
        String body=json.writeValueAsString(Map.of("managementNo",m,"configuration",config));
        var denied=mvc.perform(post("/biz/management/brands/"+b+"/stored-value/issuer").header("Authorization","Bearer "+token).header("Idempotency-Key","I1").contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
        assertThat(json.readTree(denied.getContentAsString()).path("code").asInt()).isNotZero();
        var grant=mvc.perform(post("/biz/management/spaces/"+m+"/grants").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("actorNo","MCH:"+account,"resourceType","ENTITY","resourceNo",entity,"permissionCode","sv.issuer.settlement.authorize")))).andReturn().getResponse();
        assertThat(json.readTree(grant.getContentAsString()).path("code").asInt()).as(grant.getContentAsString()).isZero();
        for(int i=0;i<2;i++) {
            var response=mvc.perform(post("/biz/management/brands/"+b+"/stored-value/issuer").header("Authorization","Bearer "+token).header("Idempotency-Key","I1").contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
            var result=json.readTree(response.getContentAsString());assertThat(result.path("code").asInt()).as(response.getContentAsString()).isZero();assertThat(result.path("data").path("status").asString()).isEqualTo("DRAFT");
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sv_issuer_version",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sv_account",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sv_recharge",Long.class)).isZero();

        String issuerNo=jdbc.queryForObject("SELECT issuer_no FROM sv_issuer",String.class);
        var edit=new LinkedHashMap<String,Object>(config);edit.put("name","Updated issuer");
        String changed=json.writeValueAsString(Map.of("managementNo",m,"configuration",edit));
        var preview=mvc.perform(post("/biz/management/stored-value/issuers/"+issuerNo+"/configuration-checks").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(changed)).andReturn().getResponse();
        assertThat(json.readTree(preview.getContentAsString()).path("data").path("allowed").asBoolean()).as(preview.getContentAsString()).isTrue();
        for(int n=0;n<2;n++){
            var saved=mvc.perform(post("/biz/management/stored-value/issuers/"+issuerNo+"/configuration-versions").header("Authorization","Bearer "+token).header("Idempotency-Key","I2").contentType(MediaType.APPLICATION_JSON).content(changed)).andReturn().getResponse();
            assertThat(json.readTree(saved.getContentAsString()).path("data").path("revision").asLong()).as(saved.getContentAsString()).isEqualTo(1);
        }
        var collection=mvc.perform(get("/biz/management/stored-value/issuers/"+issuerNo+"/collection-routes").param("managementNo",m).header("Authorization","Bearer "+token)).andReturn().getResponse();
        assertThat(json.readTree(collection.getContentAsString()).path("data").size()).as(collection.getContentAsString()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sv_issuer_version",Long.class)).isEqualTo(2);
        jdbc.update("INSERT INTO mch_payment_merchant(entity_no,pay_merchant_no,pay_channel,apply_status,pay_methods,store_no,tenant_no,created_at,updated_at) VALUES('E1','PM_TEST','WECHAT','ACTIVE',?, '', 'MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)","[\"JSAPI\"]");
        for(var permission:List.of(Map.of("resourceType","ENTITY","resourceNo","E1","permissionCode","entity.read"),Map.of("resourceType","ENTITY","resourceNo","E1","permissionCode","payment.merchant.read"),Map.of("resourceType","PAYMENT_MERCHANT","resourceNo","PM_TEST","permissionCode","payment.merchant.read"))){
            var command=new LinkedHashMap<String,Object>(permission);command.put("actorNo","MCH:"+account);
            var response=mvc.perform(post("/biz/management/spaces/"+m+"/grants").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(command))).andReturn().getResponse();assertThat(json.readTree(response.getContentAsString()).path("code").asInt()).as(response.getContentAsString()).isZero();
        }
        var online=new LinkedHashMap<String,Object>(route);online.put("paymentMerchantNo","PM_TEST");online.put("channel","WECHAT");online.put("scene","JSAPI");
        var withOnline=new LinkedHashMap<String,Object>(edit);withOnline.put("expectedRevision",1);withOnline.put("routes",List.of(route,online));
        var configured=mvc.perform(post("/biz/management/stored-value/issuers/"+issuerNo+"/configuration-versions").header("Authorization","Bearer "+token).header("Idempotency-Key","I_ROUTE").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("managementNo",m,"configuration",withOnline)))).andReturn().getResponse();assertThat(json.readTree(configured.getContentAsString()).path("code").asInt()).as(configured.getContentAsString()).isZero();
        var live=mvc.perform(get("/biz/management/stored-value/issuers/"+issuerNo+"/collection-routes").param("managementNo",m).header("Authorization","Bearer "+token)).andReturn().getResponse();assertThat(json.readTree(live.getContentAsString()).path("data").size()).isEqualTo(2);
        jdbc.update("UPDATE mch_management_grant SET status='REVOKED' WHERE management_no=? AND resource_type='PAYMENT_MERCHANT'",m);
        var revokedRoute=mvc.perform(get("/biz/management/stored-value/issuers/"+issuerNo+"/collection-routes").param("managementNo",m).header("Authorization","Bearer "+token)).andReturn().getResponse();assertThat(json.readTree(revokedRoute.getContentAsString()).path("code").asInt()).as("revoked merchant must not remain authorized").isNotZero();
        jdbc.update("UPDATE mch_management_grant SET status='ACTIVE' WHERE management_no=? AND resource_type='PAYMENT_MERCHANT'",m);
        jdbc.update("UPDATE mch_payment_merchant SET apply_status='FROZEN' WHERE pay_merchant_no='PM_TEST'");
        var frozenRoute=mvc.perform(get("/biz/management/stored-value/issuers/"+issuerNo+"/collection-routes").param("managementNo",m).header("Authorization","Bearer "+token)).andReturn().getResponse();assertThat(json.readTree(frozenRoute.getContentAsString()).path("code").asInt()).as("disabled merchant must not remain authorized").isNotZero();
        jdbc.update("UPDATE mch_payment_merchant SET apply_status='ACTIVE',pay_methods='[]' WHERE pay_merchant_no='PM_TEST'");
        var removedMethod=mvc.perform(get("/biz/management/stored-value/issuers/"+issuerNo+"/collection-routes").param("managementNo",m).header("Authorization","Bearer "+token)).andReturn().getResponse();assertThat(json.readTree(removedMethod.getContentAsString()).path("code").asInt()).as("removed method must not remain authorized").isNotZero();
        jdbc.update("UPDATE mch_management_grant SET status='REVOKED' WHERE management_no=? AND resource_type='ENTITY' AND permission_code='sv.issuer.settlement.authorize'",m);
        edit.put("expectedRevision",1);edit.put("name","Unauthorized edit");
        var revoked=mvc.perform(post("/biz/management/stored-value/issuers/"+issuerNo+"/configuration-versions").header("Authorization","Bearer "+token).header("Idempotency-Key","I3").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("managementNo",m,"configuration",edit)))).andReturn().getResponse();
        assertThat(json.readTree(revoked.getContentAsString()).path("code").asInt()).isNotZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sv_issuer_version",Long.class)).isEqualTo(3);
    }
}
