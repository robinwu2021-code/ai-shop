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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 运营账号初始密码改走邮件 —— **这次改造的核心断言在这里**。
 *
 * <p>整套用例在 {@code testcfg} 里用 {@code response} 模式（建完号要立刻用初始密码
 * 登录去验角色与权限），所以这个类单独用**生产默认值** {@code mail} 跑。
 *
 * <p><b>改的是什么</b>：此前 {@code createStaff} 把明文放进响应体，ops-web 弹窗显示，
 * 管理员抄下来转告本人。于是这串明文走过「后端 → 网络 → 浏览器内存 → 屏幕」，
 * 会进浏览器网络面板、会被截图、会被复制进聊天工具。
 * 而<b>管理员本人不该知道另一个人的密码</b> —— {@code mustChangePassword}
 * 只保证「本人首登后会变」，不保证「管理员在这之前没登过」。
 */
@SpringBootTest(properties = {
        // 自己一个内存库，理由见 OtpRateLimitFlowTest 顶部
        "spring.datasource.url=jdbc:h2:mem:ops-pwd;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "shop.ops.password-delivery=mail",
})
@ActiveProfiles("test")
@DisplayName("运营端初始密码邮件交付")
class OpsPasswordDeliveryFlowTest {

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
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("token").asString();
    }

    private JsonNode createStaff(String token, String username) throws Exception {
        String body = mvc().perform(post("/ops/staffs").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"realName\":\"新同事\","
                                + "\"roles\":[\"RISK\"]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data");
    }

    @Test
    @DisplayName("★★★ 响应体里没有明文密码 —— 这是整次改造的目的")
    void responseCarriesNoPlaintextPassword() throws Exception {
        String token = adminToken();
        JsonNode data = createStaff(token, "mail-deliver-1@neargo.ai");

        assertThat(data.get("initialPassword").isNull())
                .as("mail 模式下明文不该出现在响应体里 —— 出现了就等于这次改造没做")
                .isTrue();
        // 界面要显示「已发送至 xxx」，所以掩码后的收件人仍然返回
        assertThat(data.get("deliveredTo").asString()).contains("***").contains("@neargo.ai");
    }

    @Test
    @DisplayName("★★★ 密码真的发到了本人邮箱，且首登强制改密的闸仍在")
    void passwordIsMailedToTheStaffThemselves() throws Exception {
        String token = adminToken();
        String username = "mail-deliver-2@neargo.ai";
        JsonNode data = createStaff(token, username);

        StubMailGateway.Sent sent = mailStub.last();
        assertThat(sent).as("一封都没发出去").isNotNull();
        assertThat(sent.to())
                .as("**收件人必须是本人**，不是操作的管理员 —— 发错人就等于没改")
                .isEqualTo(username);
        assertThat(sent.body()).contains(username);

        assertThat(data.get("staff").get("mustChangePassword").asBoolean())
                .as("邮件交付不替代首登改密：邮箱本身也可能被别人看到")
                .isTrue();
    }

    @Test
    @DisplayName("★★ 邮件里带的密码能真的登进去 —— 否则「发了」等于没发")
    void mailedPasswordActuallyWorks() throws Exception {
        String token = adminToken();
        String username = "mail-deliver-3@neargo.ai";
        createStaff(token, username);

        String body = mailStub.last().body();
        // 正文形如「初始密码：Ab3xY9zQ」
        String password = body.lines()
                .filter(l -> l.startsWith("初始密码："))
                .map(l -> l.substring("初始密码：".length()).trim())
                .findFirst().orElseThrow(() -> new AssertionError("邮件正文里找不到初始密码：\n" + body));

        mvc().perform(post("/ops/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(jsonPath("$.code").value(0));
    }
}
