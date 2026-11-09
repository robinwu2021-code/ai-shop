package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ai.neargo.shop.common.OtpStore;
import ai.neargo.shop.support.TestLogin;
import org.junit.jupiter.api.BeforeEach;
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

/**
 * F6：C 端工单与商户回报按 {@code source} 隔离，不靠两套账号号段「恰好不撞」。
 *
 * <p>两边的数据都用<b>同一个 user_no</b> 造：去掉 {@code source} 条件，这两条就会红。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("工单来源隔离：C 端与商户互相看不到")
class MessageServiceTicketSourceTest {

    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper json;
    @Autowired OtpStore otpStore;
    @Autowired JdbcTemplate jdbc;
    MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    @DisplayName("★★★ C 端「我的工单」看不到同一 user_no 的商户回报，详情也打不开")
    void consumer_tickets_exclude_merchant_feedback_with_same_user_no() throws Exception {
        String token = TestLogin.consumer(mvc, json, otpStore, "13766200201");
        String body = mvc.perform(post("/mp/ticket").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"subject\":\"少发\",\"content\":\"详情\"}"))
                .andReturn().getResponse().getContentAsString();
        String mine = json.readTree(body).path("data").path("ticketNo").asString();
        String userNo = jdbc.queryForObject("select user_no from notify_ticket where ticket_no=?", String.class, mine);
        insert("TKSRCB" + userNo, userNo, "B_MERCHANT");

        JsonNode list = read(token, "/mp/ticket").path("data");
        assertThat(list.toString()).contains(mine).doesNotContain("TKSRCB" + userNo);
        assertThat(read(token, "/mp/ticket/TKSRCB" + userNo).path("code").asInt()).isEqualTo(10404);
    }

    @Test
    @DisplayName("★★★ 商户「我的回报」看不到同一 user_no 的 C 端工单")
    void merchant_feedback_excludes_consumer_tickets() throws Exception {
        String token = TestLogin.merchantOwner(mvc, json, otpStore, "13766200202");
        String body = mvc.perform(post("/biz/feedback").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"BUG\",\"description\":\"商户的\",\"appVersion\":\"0.1.0\"}"))
                .andReturn().getResponse().getContentAsString();
        String mine = json.readTree(body).path("data").path("feedbackNo").asString();
        String userNo = jdbc.queryForObject("select user_no from notify_ticket where ticket_no=?", String.class, mine);
        insert("TKSRCC" + userNo, userNo, "C_USER");

        JsonNode list = read(token, "/biz/feedback/mine").path("data");
        assertThat(list.toString()).contains(mine).doesNotContain("TKSRCC" + userNo);
    }

    private void insert(String ticketNo, String userNo, String source) {
        jdbc.update("insert into notify_ticket (ticket_no, user_no, subject, content, status, source, tenant_no,"
                + " created_at, updated_at) values (?,?, '另一边的', '另一边的', 'OPEN', ?, 'MAIN',"
                + " CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", ticketNo, userNo, source);
    }

    private JsonNode read(String token, String path) throws Exception {
        return json.readTree(mvc.perform(get(path).header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString());
    }
}
