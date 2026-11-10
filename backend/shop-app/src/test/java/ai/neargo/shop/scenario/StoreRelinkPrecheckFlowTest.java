package ai.neargo.shop.scenario;

import ai.neargo.shop.common.OtpStore;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest
@ActiveProfiles("test")
class StoreRelinkPrecheckFlowTest {
    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper json;
    @Autowired OtpStore otp;
    @Autowired JdbcTemplate jdbc;
    MerchantPlatformOnboardingFlowTest fixture() {
        var f=new MerchantPlatformOnboardingFlowTest(); f.context=context;f.json=json;f.otp=otp;f.jdbc=jdbc;return f;
    }
    String target(MerchantPlatformOnboardingFlowTest.Shop s) {
        String no="E"+UUID.randomUUID().toString().replace("-","");
        jdbc.update("INSERT INTO mch_entity(entity_no,name,status,market,created_at,updated_at) SELECT ?,name,'ACTIVE',market,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP FROM mch_entity WHERE entity_no=?",no,s.entity());
        jdbc.update("INSERT INTO mch_account(mch_account_no,entity_no,user_no,is_owner,is_primary,status,created_at,updated_at) SELECT ?,?,user_no,1,0,'ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP FROM mch_account WHERE entity_no=? AND is_owner=1",
                "A"+UUID.randomUUID().toString().replace("-",""),no,s.entity());
        return no;
    }
    JsonNode check(MerchantPlatformOnboardingFlowTest.Shop s,String target) throws Exception {
        var result=fixture().mvc().perform(get("/biz/store/"+s.store()+"/entity/relink-check")
                .header("Authorization","Bearer "+s.token()).header("X-Store-No",s.store()).header("X-Entity-No",s.entity())
                .param("entityNo",target)).andReturn().getResponse();
        return json.readTree(result.getContentAsString());
    }
    @Test void cleanSnapshotNeverAuthorizesMutationAndDoesNotWrite() throws Exception {
        var s=fixture().shop();String to=target(s);
        int before=jdbc.queryForObject("SELECT COUNT(*) FROM mch_store_role WHERE store_no=?",Integer.class,s.store());
        var result=check(s,to);assertThat(result.path("code").asInt()).as(result.toString()).isZero();
        var data=result.path("data");assertThat(data.path("allowed").asBoolean()).isFalse();
        assertThat(data.path("executionReady").asBoolean()).isFalse();
        assertThat(data.path("blockers").toString()).contains("PAY_SERVICE_UNKNOWN","RELINK_CONCURRENCY_UNAVAILABLE","INVENTORY_HISTORY_UNKNOWN");
        assertThat(data.path("blockers").toString()).doesNotContain("TARGET_NOT_OWNED","TARGET_NOT_ACTIVE","SOURCE_NOT_PLACEHOLDER","HAS_TRADES","APPLY_PENDING");
        assertThat(jdbc.queryForObject("SELECT entity_no FROM mch_store WHERE store_no=?",String.class,s.store())).isEqualTo(s.entity());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mch_store_role WHERE store_no=?",Integer.class,s.store())).isEqualTo(before);
        var rejected=fixture().mvc().perform(post("/biz/store/"+s.store()+"/entity")
                .header("Authorization","Bearer "+s.token()).header("X-Store-No",s.store()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"entityNo\":\""+to+"\"}")).andReturn().getResponse();
        assertThat(json.readTree(rejected.getContentAsString()).path("code").asInt()).isNotZero();
        assertThat(jdbc.queryForObject("SELECT entity_no FROM mch_store WHERE store_no=?",String.class,s.store())).isEqualTo(s.entity());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mch_store_entity_claim WHERE store_no=?",Integer.class,s.store())).isZero();
    }
    @Test void foreignAndInactiveTargetsAreDistinctWithoutDisclosingForeignDetails() throws Exception {
        var s=fixture().shop();var other=fixture().shop();
        assertThat(check(s,other.entity()).path("data").path("blockers").toString()).contains("TARGET_NOT_OWNED").doesNotContain("TARGET_NOT_ACTIVE");
        String to=target(s);jdbc.update("UPDATE mch_entity SET status='SUSPENDED' WHERE entity_no=?",to);
        assertThat(check(s,to).path("data").path("blockers").toString()).contains("TARGET_NOT_ACTIVE").doesNotContain("TARGET_NOT_OWNED");
    }
    @Test void employeeCountIsDistinctAndPrecheckKeepsAllGrants() throws Exception {
        var s=fixture().shop();String to=target(s),staff="A"+UUID.randomUUID().toString().replace("-","");
        jdbc.update("INSERT INTO mch_account(mch_account_no,entity_no,is_owner,status,created_at,updated_at) VALUES(?,?,0,'ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",staff,s.entity());
        for (String role:new String[]{"CLERK","MANAGER"}) jdbc.update("INSERT INTO mch_store_role(mch_account_no,store_no,role,created_at,updated_at) VALUES(?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",staff,s.store(),role);
        assertThat(check(s,to).path("data").path("revokedStaff").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mch_store_role WHERE store_no=? AND mch_account_no=?",Integer.class,s.store(),staff)).isEqualTo(2);
    }
    @Test void currentPlatformApplicationAndVoidedOfflineHistoryAreBlockers() throws Exception {
        var f=fixture();var s=f.shop();String to=target(s);
        assertThat(f.submit(s,f.body("Pending platform")).path("code").asInt()).isZero();
        jdbc.update("INSERT INTO ord_offline_record(record_no,entity_no,store_no,amount_minor,currency,method,received_at,status,recorded_by,idem_key_hash,request_hash,created_at,updated_at,deleted) VALUES(?,?,?,1,'CNY','CASH',CURRENT_TIMESTAMP,'VOID','LOCAL',?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,1)",
                "R"+UUID.randomUUID().toString().replace("-",""),s.entity(),s.store(),"0".repeat(64),"1".repeat(64));
        assertThat(check(s,to).path("data").path("blockers").toString()).contains("HAS_TRADES","APPLY_PENDING");
    }
    @Test void mismatchedSelectedStoreCannotReadAnotherStoresDiagnostics() throws Exception {
        var s=fixture().shop();String to=target(s);
        var result=fixture().mvc().perform(get("/biz/store/"+s.store()+"/entity/relink-check")
                .header("Authorization","Bearer "+s.token()).header("X-Store-No","FOREIGN").param("entityNo",to)).andReturn().getResponse();
        var body=json.readTree(result.getContentAsString());assertThat(body.path("code").asInt()).isNotZero();
        assertThat(body.path("data").isNull()).isTrue();
    }
}
