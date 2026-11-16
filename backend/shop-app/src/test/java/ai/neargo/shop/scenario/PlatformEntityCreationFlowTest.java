package ai.neargo.shop.scenario;

import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.TokenStore;
import ai.neargo.shop.common.OtpStore;
import ai.neargo.shop.support.TestLogin;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** The entity resource must be reachable before the account owns any store. */
@SpringBootTest
@ActiveProfiles("test")
class PlatformEntityCreationFlowTest {
    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper json;
    @Autowired OtpStore otp;
    @Autowired JdbcTemplate jdbc;
    @Autowired TokenStore tokens;
    MockMvc mvc(){return MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();}
    String login() throws Exception {
        String phone="159"+String.format("%08d",Math.abs(UUID.randomUUID().hashCode()%100000000));
        return TestLogin.merchantOwner(mvc(),json,otp,phone);
    }
    JsonNode create(String token,String key,String name) throws Exception {
        return json.readTree(mvc().perform(post("/biz/entities").header("Authorization","Bearer "+token)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(
                        Map.of("requestNo",key,"name",name,"legalForm","ENTERPRISE"))))
                .andReturn().getResponse().getContentAsString());
    }
    @Test void personalAccountCreatesAndRecoversWithoutSelectedStore() throws Exception {
        String token=login();var first=create(token,"INDEPENDENT-1"," Independent ");
        assertThat(first.path("code").asInt()).as(first.toString()).isZero();
        var entity=first.path("data").path("entity");String no=entity.path("entityNo").asString();
        assertThat(entity.path("status").asString()).isEqualTo("PENDING_LICENSE");
        assertThat(entity.path("review").asString()).isEqualTo("NONE");
        assertThat(entity.path("verified").asBoolean()).isFalse();
        assertThat(entity.path("canManage").asBoolean()).isTrue();
        assertThat(entity.path("storeCount").asInt()).isZero();
        var detail=json.readTree(mvc().perform(get("/biz/entity/"+no)
                .header("Authorization","Bearer "+token)).andReturn().getResponse().getContentAsString());
        assertThat(detail.path("code").asInt()).as(detail.toString()).isZero();
        assertThat(detail.path("data").path("entity").path("entityNo").asString()).isEqualTo(no);
        var forbidden=json.readTree(mvc().perform(get("/biz/entity/"+no)
                .header("Authorization","Bearer "+login())).andReturn().getResponse().getContentAsString());
        assertThat(forbidden.path("code").asInt()).as(forbidden.toString()).isEqualTo(10403);

        var listed=json.readTree(mvc().perform(get("/biz/entities").header("Authorization","Bearer "+token))
                .andReturn().getResponse().getContentAsString());
        assertThat(listed.path("code").asInt()).as(listed.toString()).isZero();
        assertThat(listed.path("data").size()).isEqualTo(1);
        assertThat(listed.path("data").get(0).path("entityNo").asString()).isEqualTo(no);
        assertThat(listed.path("data").get(0).path("storeCount").asInt()).isZero();
        String staff=jdbc.queryForObject("SELECT mch_account_no FROM mch_account WHERE entity_no=? AND is_owner=1",
                String.class,no);
        String staffToken=tokens.issue(TokenStore.SessionData.of(LoginUser.merchant(staff,"staff")));
        var denied=json.readTree(mvc().perform(get("/biz/entities").header("Authorization","Bearer "+staffToken))
                .andReturn().getResponse().getContentAsString());
        assertThat(denied.path("code").asInt()).as(denied.toString()).isEqualTo(10403);
        var other=json.readTree(mvc().perform(get("/biz/entities").header("Authorization","Bearer "+login()))
                .andReturn().getResponse().getContentAsString());
        assertThat(other.path("code").asInt()).as(other.toString()).isZero();
        assertThat(other.path("data").size()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mch_store WHERE entity_no=?",Integer.class,no)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mch_payment_merchant WHERE entity_no=?",Integer.class,no)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mch_entity_apply WHERE entity_no=?",Integer.class,no)).isZero();
        assertThat(jdbc.queryForObject("SELECT plan_code FROM mch_entity_plan WHERE entity_no=?",String.class,no)).isEqualTo("FREE");
        var replay=create(token,"INDEPENDENT-1"," Independent ");
        assertThat(replay.path("data").path("replayed").asBoolean()).as(replay.toString()).isTrue();
        assertThat(replay.path("data").path("entity").path("entityNo").asString()).isEqualTo(no);
        var result=json.readTree(mvc().perform(get("/biz/entities/create-results/INDEPENDENT-1")
                .header("Authorization","Bearer "+token)).andReturn().getResponse().getContentAsString());
        assertThat(result.path("code").asInt()).as(result.toString()).isZero();
        assertThat(result.path("data").path("entity").path("entityNo").asString()).isEqualTo(no);
        assertThat(create(token,"INDEPENDENT-1","Changed").path("code").asInt()).isEqualTo(10409);
        // Quick-start may create its own placeholder, but cannot turn the independent entity into a store.
        var quick=json.readTree(mvc().perform(post("/biz/merchant/quick-start")
                .header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"storeName\":\"Separate shop\"}")).andReturn().getResponse().getContentAsString());
        assertThat(quick.path("code").asInt()).as(quick.toString()).isZero();
        assertThat(quick.path("data").path("merchantNo").asString()).isNotEqualTo(no);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mch_store WHERE entity_no=?",Integer.class,no)).isZero();
    }
    @Test void validationRejectsBadKeyAndUnknownLegalFormBeforeCreating() throws Exception {
        String token=login();
        assertThat(create(token,"bad/key","Name").path("code").asInt()).isEqualTo(10400);
        assertThat(create(token,"GOOD"," ").path("code").asInt()).isEqualTo(10400);
        var bad=json.readTree(mvc().perform(post("/biz/entities").header("Authorization","Bearer "+token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestNo\":\"GOOD\",\"name\":\"Name\",\"legalForm\":\"UNKNOWN\"}"))
                .andReturn().getResponse().getContentAsString());
        assertThat(bad.path("code").asInt()).as(bad.toString()).isEqualTo(10400);
    }
}
