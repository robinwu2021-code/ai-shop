package ai.neargo.shop.scenario;

import ai.neargo.shop.support.TestLogin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 客服工单闭环（P-14.2）。
 *
 * <p><b>这些用例断言的是「用户真的收到了回复」，不是「接口返回 200」。</b>
 * 这个缺陷此前之所以没人发现，正是因为每一端单看都是正常的：
 * C 端提单成功、列表能查、详情页有回复展示位；只是那个展示位永远是空的，
 * 而且不报任何错。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("客服工单：用户提得上，客服接得住")
class OpsTicketFlowTest {

    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;


    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                        .springSecurity())
                .build();
    }

    @Test
    @DisplayName("★ 用户提单 → 客服回复 → 用户在自己的详情页看到回复")
    void userSeesTheReply() throws Exception {
        String user = login("13900139001");
        String ticketNo = createTicket(user, "少发了一件");

        // 回复前：展示位是空的 —— 这正是修复前的永久状态
        assertThat(ticketDetail(user, ticketNo).get("reply").isNull())
                .as("还没人回复，reply 应当为空").isTrue();

        String support = opsLogin("support", "support123");
        mvc().perform(post("/ops/tickets/" + ticketNo + "/reply")
                        .header("Authorization", "Bearer " + support)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reply\":\"已补发，明天到自提点\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        JsonNode after = ticketDetail(user, ticketNo);
        assertThat(after.get("reply").asString())
                .as("**用户必须能看到回复** —— 这条断言才是这个功能存在的理由")
                .isEqualTo("已补发，明天到自提点");
        assertThat(after.get("status").asString()).isEqualTo("REPLIED");
        assertThat(after.get("repliedAt").asLong()).isPositive();
    }

    @Test
    @DisplayName("平台列表看得到别人的单（C 端只看得到自己的）")
    void opsListCrossesUsers() throws Exception {
        String a = login("13900139002");
        String ticketNo = createTicket(a, "甲的单");
        String support = opsLogin("support", "support123");

        String body = mvc().perform(get("/ops/tickets")
                        .header("Authorization", "Bearer " + support))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(json.readTree(body).get("data").toString())
                .as("平台视角必须跨用户 —— 只查自己的话客服永远看不到任何单")
                .contains(ticketNo);
    }

    @Test
    @DisplayName("空回复被拒：否则单子离开待处理队列而用户什么也没收到")
    void emptyReplyRejected() throws Exception {
        String user = login("13900139003");
        String ticketNo = createTicket(user, "空回复测试");
        String support = opsLogin("support", "support123");

        mvc().perform(post("/ops/tickets/" + ticketNo + "/reply")
                        .header("Authorization", "Bearer " + support)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reply\":\"  \"}"))
                .andExpect(jsonPath("$.code").value(10400));

        assertThat(ticketDetail(user, ticketNo).get("status").asString())
                .as("被拒之后状态不能变").isEqualTo("OPEN");
    }

    @Test
    @DisplayName("关闭幂等；已关闭的单不能再回复")
    void closeIsIdempotentAndBlocksReply() throws Exception {
        String user = login("13900139004");
        String ticketNo = createTicket(user, "关单测试");
        String support = opsLogin("support", "support123");

        for (int i = 0; i < 2; i++) {
            mvc().perform(post("/ops/tickets/" + ticketNo + "/close")
                            .header("Authorization", "Bearer " + support))
                    .andExpect(jsonPath("$.code").value(0));
        }
        assertThat(ticketDetail(user, ticketNo).get("status").asString()).isEqualTo("CLOSED");

        mvc().perform(post("/ops/tickets/" + ticketNo + "/reply")
                        .header("Authorization", "Bearer " + support)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reply\":\"迟到的回复\"}"))
                .andExpect(jsonPath("$.code").value(10409));
    }

    @Test
    @DisplayName("没有 ticket:handle 的角色回不了（BD 不是客服）")
    void bdCannotReply() throws Exception {
        String user = login("13900139005");
        String ticketNo = createTicket(user, "越权测试");
        String bd = opsLogin("bd", "bd123");

        mvc().perform(post("/ops/tickets/" + ticketNo + "/reply")
                        .header("Authorization", "Bearer " + bd)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reply\":\"我不该能回\"}"))
                // @PreAuthorize 抛 AccessDeniedException，由 GlobalExceptionHandler
                // 转成契约包（HTTP 200 + code 10403），不是 HTTP 403
                .andExpect(jsonPath("$.code").value(10403));
    }

    // ---------------------------------------------------------------- helpers

    private String createTicket(String token, String subject) throws Exception {
        String body = mvc().perform(post("/mp/ticket").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject\":\"" + subject + "\",\"content\":\"详情\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("ticketNo").asString();
    }

    private JsonNode ticketDetail(String token, String ticketNo) throws Exception {
        String body = mvc().perform(get("/mp/ticket/" + ticketNo)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data");
    }

    private String login(String phone) throws Exception {
        return TestLogin.consumer(mvc(), json, otpStore, phone);
    }

    private String opsLogin(String username, String password) throws Exception {
        String body = mvc().perform(post("/ops/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("token").asString();
    }
}
