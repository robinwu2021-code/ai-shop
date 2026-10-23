package ai.neargo.shop.scenario;

import ai.neargo.shop.auth.*;
import java.nio.file.*;
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
        String ddl=Files.readString(Path.of("src/main/resources/db/migration/V548__stored_value_issuer_foundation.sql"))
                .replaceAll("(?m)^--.*$", "").replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_520_ci", "")
                .replace(" JSON", " VARCHAR(10000)").replace("CHECK (scope_type IN ('BRAND','STORE'))", "CHECK (CASE scope_type WHEN 'BRAND' THEN TRUE WHEN 'STORE' THEN TRUE ELSE FALSE END)");
        for(String part:ddl.split(";")) if(!part.isBlank()) {
            var m=java.util.regex.Pattern.compile("CREATE TABLE ([a-z_]+)").matcher(part);
            if(m.find()) part=part.replace("KEY uk_","KEY "+m.group(1)+"_uk_").replace("KEY ix_","KEY "+m.group(1)+"_ix_");
            jdbc.execute(part);
        }
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
    }
}
