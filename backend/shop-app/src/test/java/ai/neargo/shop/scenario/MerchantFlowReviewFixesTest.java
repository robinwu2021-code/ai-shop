package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ai.neargo.shop.common.OtpStore;
import ai.neargo.shop.support.TestLogin;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Regressions for the 2026-11-16 main-flow review (docs/handoff/bapp-api-alignment-2026-11-16/flow-review.md). */
@SpringBootTest
@ActiveProfiles("test")
class MerchantFlowReviewFixesTest {

    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper json;
    @Autowired private OtpStore otpStore;
    @Autowired private JdbcTemplate jdbc;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    private static String phone() {
        return "137" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
    }

    private JsonNode postJson(String path, String token, String body) throws Exception {
        var req = post(path).contentType(MediaType.APPLICATION_JSON).content(body);
        if (token != null) req.header("Authorization", "Bearer " + token);
        return json.readTree(mvc().perform(req).andReturn().getResponse().getContentAsString());
    }

    @Test
    @DisplayName("Review #3: a store settings write without a selected store is refused, not a silent 200")
    void settingsWriteNeedsSelectedStore() throws Exception {
        String phone = phone();
        String token = TestLogin.merchantOwner(mvc(), json, otpStore, phone);
        JsonNode r = postJson("/biz/store/settings/hours", token, "{\"expectedVersion\":\"x\",\"openHours\":\"09:00-18:00\"}");
        assertThat(r.path("code").asInt()).as(r.toString()).isNotZero();
    }

    @Test
    @DisplayName("Review #6: an owner who registers through the unified login gets a person record, like the old login")
    void unifiedLoginBindsPerson() throws Exception {
        String phone = phone();
        mvc().perform(post("/biz/auth/otp/send").contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"" + phone + "\"}"));
        String code = otpStore.peek(phone).orElseThrow();
        JsonNode listed = postJson("/biz/auth/identities", null,
                "{\"grantType\":\"PHONE_OTP\",\"principal\":\"" + phone + "\",\"credential\":\"" + code + "\",\"agreed\":true}");
        assertThat(listed.path("code").asInt()).as(listed.toString()).isZero();
        String ticket = listed.path("data").path("ticket").asString();
        JsonNode entered = postJson("/biz/auth/identities/enter", null,
                "{\"ticket\":\"" + ticket + "\",\"identityNo\":\"NEW_OWNER\"}");
        assertThat(entered.path("code").asInt()).as(entered.toString()).isZero();

        String userNo = jdbc.queryForObject("SELECT user_no FROM usr_identity WHERE identity_type='PHONE' AND identity_value=?",
                String.class, phone);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM usr_person WHERE user_no=?", Integer.class, userNo))
                .isEqualTo(1);
        assertThat(mvc().perform(get("/biz/auth/account").header("Authorization",
                "Bearer " + entered.path("data").path("token").asString())).andReturn().getResponse().getStatus())
                .isEqualTo(200);
    }
}
