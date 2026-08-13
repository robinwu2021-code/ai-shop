package ai.neargo.shop.scenario;

import ai.neargo.shop.channel.notify.port.StubMailGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 运营端「忘记密码」—— **这条路径此前完全不存在**。
 *
 * <p>`OpsService` 只有 `login` 与 `changeOwnPassword`：管理员侧没有重置入口、
 * 员工侧没有、登录页也没有。运营忘了密码只能找人改库。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("运营端忘记密码")
class OpsForgotPasswordFlowTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private StubMailGateway mailStub;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                        .springSecurity())
                .build();
    }

    @BeforeEach
    void clearMail() {
        mailStub.clear();
    }

    private String adminToken() throws Exception {
        String body = mvc().perform(post("/ops/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("token").asString();
    }

    /** 建一个真账号，返回它的初始密码（testcfg 是 response 模式） */
    private String createStaff(String username) throws Exception {
        String body = mvc().perform(post("/ops/staffs").header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"realName\":\"忘密的人\","
                                + "\"roles\":[\"RISK\"]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("initialPassword").asString();
    }

    private void forgot(String username) throws Exception {
        mvc().perform(post("/ops/auth/forgot")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\"}"))
                .andExpect(jsonPath("$.code").value(0));
    }

    /** 从邮件正文里抠出重置码 */
    private String tokenFromMail() {
        StubMailGateway.Sent sent = mailStub.last();
        assertThat(sent).as("一封重置邮件都没发出去").isNotNull();
        return sent.body().lines().map(String::trim)
                .filter(l -> !l.isEmpty() && !l.contains("：") && !l.contains("，") && l.length() > 20)
                .findFirst().orElseThrow(() -> new AssertionError("正文里找不到重置码：\n" + sent.body()));
    }

    @Test
    @DisplayName("★★★ 账号不存在也返回成功且不发信 —— 区分开就等于送了个账号探测器")
    void unknownAccountLooksIdentical() throws Exception {
        forgot("nobody-here@neargo.ai");
        assertThat(mailStub.all())
                .as("不存在的账号不该发信，但**响应必须与存在时一模一样**")
                .isEmpty();
    }

    @Test
    @DisplayName("★★★ 重置码能设新密码，旧密码随即失效")
    void resetWorksAndOldPasswordDies() throws Exception {
        String username = "forgot-1@neargo.ai";
        String oldPassword = createStaff(username);
        mailStub.clear();

        forgot(username);
        String token = tokenFromMail();

        String newPassword = "NewPass2026!";
        mvc().perform(post("/ops/auth/reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\",\"newPassword\":\"" + newPassword + "\"}"))
                .andExpect(jsonPath("$.code").value(0));

        mvc().perform(post("/ops/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + newPassword + "\"}"))
                .andExpect(jsonPath("$.code").value(0));

        mvc().perform(post("/ops/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + oldPassword + "\"}"))
                .andExpect(jsonPath("$.code").value(10401));
    }

    @Test
    @DisplayName("★★★ 重置码一次性 —— 用过的再用必须失效（邮件会留在收件箱里）")
    void tokenIsSingleUse() throws Exception {
        String username = "forgot-2@neargo.ai";
        createStaff(username);
        mailStub.clear();

        forgot(username);
        String token = tokenFromMail();
        String body = "{\"token\":\"" + token + "\",\"newPassword\":\"FirstPass2026!\"}";

        mvc().perform(post("/ops/auth/reset").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.code").value(0));
        mvc().perform(post("/ops/auth/reset").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\",\"newPassword\":\"SecondPass2026!\"}"))
                .andExpect(jsonPath("$.code").value(10453));
    }

    @Test
    @DisplayName("★★ 连点两次「忘记密码」时，旧的那个码作废 —— 两个都能用的话用户不知道点哪个")
    void newRequestInvalidatesTheOldToken() throws Exception {
        String username = "forgot-3@neargo.ai";
        createStaff(username);
        mailStub.clear();

        forgot(username);
        String first = tokenFromMail();
        forgot(username);
        String second = tokenFromMail();
        assertThat(second).isNotEqualTo(first);

        mvc().perform(post("/ops/auth/reset").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + first + "\",\"newPassword\":\"Whatever2026!\"}"))
                .andExpect(jsonPath("$.code").value(10453));
        mvc().perform(post("/ops/auth/reset").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + second + "\",\"newPassword\":\"Whatever2026!\"}"))
                .andExpect(jsonPath("$.code").value(0));
    }
}
