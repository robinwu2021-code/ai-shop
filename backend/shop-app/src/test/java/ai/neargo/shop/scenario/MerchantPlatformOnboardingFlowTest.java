package ai.neargo.shop.scenario;

import ai.neargo.shop.common.OtpStore;
import ai.neargo.shop.support.TestLogin;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest
@ActiveProfiles("test")
class MerchantPlatformOnboardingFlowTest {
    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper json;
    @Autowired OtpStore otp;
    @Autowired JdbcTemplate jdbc;
    record Shop(String token, String entity, String store) {}
    MockMvc mvc() { return MockMvcBuilders.webAppContextSetup(context)
            .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build(); }
    Shop shop() throws Exception {
        String phone = "159" + String.format("%08d", Math.abs(UUID.randomUUID().hashCode() % 100000000));
        String token = TestLogin.merchantOwner(mvc(), json, otp, phone);
        var response = json.readTree(mvc().perform(post("/biz/merchant/quick-start")
                .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"storeName\":\"platform probe\"}")).andReturn().getResponse().getContentAsString());
        assertThat(response.path("code").asInt()).as(response.toString()).isZero();
        String entity = response.path("data").path("merchantNo").asString();
        return new Shop(token, entity, jdbc.queryForObject("SELECT store_no FROM mch_store WHERE entity_no=? LIMIT 1", String.class, entity));
    }
    String body(String name) {
        return "{\"profile\":{\"subject\":\"PERSONAL\",\"name\":\""+name+"\",\"owner\":\"Owner\",\"ownerPhone\":\"+886912345678\",\"industry\":\"RETAIL\"},\"licenses\":[],\"agreed\":true}";
    }
    JsonNode submit(Shop shop, String body) throws Exception {
        return json.readTree(mvc().perform(post("/biz/merchant/onboarding").header("Authorization", "Bearer "+shop.token())
                .header("X-Store-No", shop.store()).header("X-Entity-No",shop.entity())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getContentAsString());
    }
    JsonNode entities(Shop shop) throws Exception {
        return json.readTree(mvc().perform(get("/biz/entities").header("Authorization", "Bearer "+shop.token())
                .header("X-Store-No", shop.store())).andReturn().getResponse().getContentAsString()).path("data");
    }
    @Test void createReplayReviseAndReviewLockKeepOneApplicationAndAgreement() throws Exception {
        var s=shop(); var first=submit(s,body("Platform One"));
        assertThat(first.path("code").asInt()).as(first.toString()).isZero();
        String no=first.path("data").path("applyNo").asString();
        assertThat(first.path("data").path("agreedAt").asLong()).isPositive();
        assertThat(first.path("data").path("nextStep").asString()).isEqualTo("PRIVATE_CHANNEL_ONBOARDING");
        var replay=submit(s,body("Platform One"));
        assertThat(replay.path("data").path("replayed").asBoolean()).as(replay.toString()).isTrue();
        var revised=submit(s,body("Platform Two"));
        assertThat(revised.path("data").path("revised").asBoolean()).as(revised.toString()).isTrue();
        assertThat(revised.path("data").path("applyNo").asString()).isEqualTo(no);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mch_entity_apply WHERE entity_no=?",Integer.class,s.entity())).isEqualTo(1);
        var list=entities(s); assertThat(list.get(0).path("review").asString()).isEqualTo("REVIEWING");
        assertThat(list.get(0).path("applyNo").asString()).isEqualTo(no);
        jdbc.update("UPDATE mch_entity_apply SET status='REVIEWING' WHERE apply_no=?",no);
        assertThat(submit(s,body("Locked")).path("code").asInt()).isEqualTo(10467);
        assertThat(jdbc.queryForObject("SELECT name FROM mch_entity_apply WHERE apply_no=?",String.class,no)).isEqualTo("Platform Two");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mch_payment_merchant WHERE entity_no=?",Integer.class,s.entity())).isZero();
    }
    @Test void privateChannelFieldsAndMissingAgreementAreRejectedBeforeWriting() throws Exception {
        var s=shop();
        assertThat(submit(s,body("No agreement").replace("true","false")).path("code").asInt()).isEqualTo(10400);
        assertThat(submit(s,body("No secrets").replace("\"agreed\":true","\"agreed\":true,\"settleAccount\":{\"account\":\"1234567890\"}")).path("code").asInt()).isEqualTo(10400);
        assertThat(submit(s,body("No channels").replace("\"agreed\":true","\"agreed\":true,\"channels\":[\"NEWEBPAY\"]")).path("code").asInt()).isEqualTo(10400);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mch_entity_apply WHERE entity_no=?",Integer.class,s.entity())).isZero();
    }
    @Test void missingStoreAndMismatchedEntityCannotWrite() throws Exception {
        var s=shop();
        var missing=json.readTree(mvc().perform(post("/biz/merchant/onboarding").header("Authorization","Bearer "+s.token())
                .contentType(MediaType.APPLICATION_JSON).content(body("Missing"))).andReturn().getResponse().getContentAsString());
        assertThat(missing.path("code").asInt()).as(missing.toString()).isEqualTo(70198);
        assertThat(submit(s,body("Wrong").replace("\"profile\":","\"entityNo\":\"OTHER\",\"profile\":")).path("code").asInt()).isNotZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mch_entity_apply WHERE entity_no=?",Integer.class,s.entity())).isZero();
    }
    @Test void rejectionReadbackAndResubmissionUseEntityLatestApplication() throws Exception {
        var s=shop(); var first=submit(s,body("Rejected")); String no=first.path("data").path("applyNo").asString();
        jdbc.update("UPDATE mch_entity_apply SET status='REJECTED',active_owner=NULL,reject_reason='platform reason' WHERE apply_no=?",no);
        var list=entities(s);assertThat(list.get(0).path("review").asString()).isEqualTo("REJECTED");
        assertThat(list.get(0).path("rejectReason").asString()).isEqualTo("platform reason");
        assertThat(list.get(0).path("owner").asString()).isEqualTo("Owner");
        var next=submit(s,body("Resubmit"));assertThat(next.path("code").asInt()).as(next.toString()).isZero();
        assertThat(next.path("data").path("applyNo").asString()).isNotEqualTo(no);
        assertThat(entities(s).get(0).path("rejectReason").asString()).isEmpty();
        var other=shop();assertThat(entities(other).get(0).path("applyNo").isNull()).isTrue();
    }
    @Test void platformBusinessAssetsAreScopedAndTaxNumberUsesApprovedQualification() throws Exception {
        var s=shop();
        String company = body("Company").replace("PERSONAL", "COMPANY")
                .replace("\"owner\":", "\"taxNo\":\"12345678\",\"owner\":")
                .replace("\"licenses\":[]", "\"licenses\":[{\"type\":\"COMPANY_REGISTRATION\",\"assetKey\":\""+s.entity()+"/_ENTITY/qual/202611/proof.jpg\"}]");
        assertThat(submit(s,company.replace(s.entity()+"/_ENTITY", "FOREIGN/_ENTITY")).path("code").asInt()).isEqualTo(10400);
        var result=submit(s,company); assertThat(result.path("code").asInt()).as(result.toString()).isZero();
        assertThat(entities(s).get(0).path("taxNo").asString()).isEqualTo("12345678");
        var detail=json.readTree(mvc().perform(get("/biz/entity/"+s.entity()).header("Authorization","Bearer "+s.token())
                .header("X-Store-No",s.store())).andReturn().getResponse().getContentAsString());
        assertThat(detail.path("data").path("entity").path("applyNo").asString()).isEqualTo(result.path("data").path("applyNo").asString());
        jdbc.update("UPDATE mch_entity SET status='ACTIVE' WHERE entity_no=?",s.entity());
        jdbc.update("INSERT INTO mch_qualification(qual_no,entity_no,qual_type,qual_name,qual_number,status,created_at,updated_at) VALUES(?,?,'BUSINESS_LICENSE','Business licence','87654321','VALID',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                "Q"+UUID.randomUUID().toString().replace("-",""),s.entity());
        var approved=entities(s).get(0);
        assertThat(approved.path("review").asString()).isEqualTo("APPROVED");
        assertThat(approved.path("taxNo").asString()).isEqualTo("87654321");
        assertThat(submit(s,company).path("code").asInt()).isEqualTo(10409);
    }
    @Test void unknownIndustryAndAnotherEntityActiveApplicationCannotBeSubmitted() throws Exception {
        var s=shop();
        assertThat(submit(s,body("Unknown").replace("RETAIL","UNKNOWN")).path("code").asInt()).isEqualTo(10400);
        var first=submit(s,body("Original"));String no=first.path("data").path("applyNo").asString();
        jdbc.update("UPDATE mch_entity_apply SET entity_no='ANOTHER-ENTITY' WHERE apply_no=?",no);
        assertThat(submit(s,body("Other")).path("code").asInt()).isEqualTo(10409);
        assertThat(jdbc.queryForObject("SELECT name FROM mch_entity_apply WHERE apply_no=?",String.class,no)).isEqualTo("Original");
    }

}
