package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ai.neargo.shop.common.OtpStore;
import ai.neargo.shop.support.TestLogin;
import ai.neargo.shop.user.service.EmailOtpService;
import java.util.UUID;
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
 * 测试身份白名单（TDD-测试身份白名单登录）。统一码 1234、统一密码 123456 是这条用例自己配的，
 * 与 166 上的值无关。判据取真实链路的痕迹：发没发短信查 {@code sys_notify_log}，存没存码查 {@code OtpStore}。
 * 每条用例用自己的探针身份，finally 里删掉自己加的行（启用上限 10 条，不还原会把别的用例挤红）。
 */
@SpringBootTest(properties = {
        "shop.auth.test-identity.enabled=true",
        "shop.auth.test-identity.code=1234",
        "shop.auth.test-identity.password=123456"})
@ActiveProfiles("test")
class TestIdentityFlowTest {

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private OtpStore otpStore;
    @Autowired
    private JdbcTemplate jdbc;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    @DisplayName("★★★ 白名单手机号：发码不发短信不存码，统一码登录注册；不在名单的号用统一码登不进")
    void whitelistedPhoneUsesUnifiedCode() throws Exception {
        String ops = TestLogin.admin(mvc(), json);
        String phone = probePhone();
        Long id = add(ops, "PHONE", phone);
        try {
            JsonNode sent = call(post("/biz/auth/otp/send"), "{\"phone\":\"" + phone + "\"}");
            assertThat(sent.path("code").asInt()).as(sent.toString()).isZero();
            assertThat(otpLogCount(phone)).as("白名单号还是发了真短信").isZero();
            assertThat(otpStore.peek(phone)).as("白名单号不该存随机码").isEmpty();

            assertThat(token(login("PHONE_OTP", phone, "1234"))).as("统一码没能登录 / 注册").startsWith("btk_");

            String stranger = probePhone();
            assertThat(login("PHONE_OTP", stranger, "1234").path("code").asInt())
                    .as("不在名单的号用统一码登进去了 —— 统一码成了万能钥匙").isNotZero();
        } finally {
            remove(ops, id);
        }
    }

    @Test
    @DisplayName("★★★ 白名单邮箱：不发信，大小写不同也认，统一码登录注册")
    void whitelistedEmailUsesUnifiedCode() throws Exception {
        String ops = TestLogin.admin(mvc(), json);
        String email = "Probe." + UUID.randomUUID().toString().substring(0, 8) + "@Example.test";
        Long id = add(ops, "EMAIL", email.toLowerCase());
        try {
            JsonNode sent = call(post("/biz/auth/email-otp/send"), "{\"email\":\"" + email + "\",\"locale\":\"zh-Hant\"}");
            assertThat(sent.path("code").asInt()).as(sent.toString()).isZero();
            assertThat(sent.path("data").path("expiresInSeconds").asLong()).isEqualTo(600);
            assertThat(otpStore.peek(EmailOtpService.key(EmailOtpService.normalize(email)))).isEmpty();

            assertThat(token(login("EMAIL_OTP", email, "1234"))).startsWith("btk_");
            assertThat(login("EMAIL_OTP", email, "9999").path("code").asInt()).as("错码也过了").isNotZero();
        } finally {
            remove(ops, id);
        }
    }

    @Test
    @DisplayName("★★★ 统一密码只登录名单里已有的账号（没设过密码也行）；名单外的账号、名单内没注册的号都不行")
    void unifiedPasswordOnlyForExistingWhitelistedAccounts() throws Exception {
        String ops = TestLogin.admin(mvc(), json);
        String phone = probePhone();
        Long id = add(ops, "PHONE", phone);
        try {
            JsonNode none = login("PASSWORD", phone, "123456");
            assertThat(none.path("code").asInt()).as("没注册的号用统一密码登进去了 —— 密码登录不建户").isNotZero();
            assertThat(jdbc.queryForObject("select count(*) from usr_identity where identity_value=?", Integer.class, phone))
                    .isZero();

            assertThat(token(login("PHONE_OTP", phone, "1234"))).startsWith("btk_");
            assertThat(token(login("PASSWORD", phone, "123456"))).as("名单内已有账号用统一密码登不进").startsWith("btk_");

            String outsider = probePhone();
            otpStore.save(outsider, "777777");
            assertThat(token(login("PHONE_OTP", outsider, "777777"))).startsWith("btk_");
            assertThat(login("PASSWORD", outsider, "123456").path("code").asInt())
                    .as("名单外的账号用统一密码登进去了").isNotZero();

            // 停用即时生效：不等 60 秒缓存
            call(post("/ops/test-identities/" + id + "/enabled").header("Authorization", "Bearer " + ops), "{\"enabled\":false}");
            assertThat(login("PASSWORD", phone, "123456").path("code").asInt()).as("停用后统一密码还能用").isNotZero();
        } finally {
            remove(ops, id);
        }
    }

    @Test
    @DisplayName("运营列表带服务器开关状态；格式不对的值拒收")
    void opsListShowsSwitchAndRejectsMalformed() throws Exception {
        String ops = TestLogin.admin(mvc(), json);
        JsonNode list = json.readTree(mvc().perform(get("/ops/test-identities").header("Authorization", "Bearer " + ops))
                .andReturn().getResponse().getContentAsString());
        assertThat(list.path("data").path("switchOn").asBoolean()).as(list.toString()).isTrue();
        JsonNode bad = call(post("/ops/test-identities").header("Authorization", "Bearer " + ops),
                "{\"identityType\":\"EMAIL\",\"identityValue\":\"not-an-email\"}");
        assertThat(bad.path("code").asInt()).isEqualTo(10400);
    }

    // ── helpers ──

    private JsonNode call(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req, String body)
            throws Exception {
        return json.readTree(mvc().perform(req.contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getContentAsString());
    }

    private JsonNode login(String grant, String principal, String credential) throws Exception {
        return call(post("/biz/auth/login"), "{\"grantType\":\"" + grant + "\",\"principal\":\"" + principal
                + "\",\"credential\":\"" + credential + "\",\"agreed\":true}");
    }

    private static String token(JsonNode r) {
        return r.path("data").path("token").asString("<" + r + ">");
    }

    private Long add(String ops, String type, String value) throws Exception {
        JsonNode r = call(post("/ops/test-identities").header("Authorization", "Bearer " + ops),
                "{\"identityType\":\"" + type + "\",\"identityValue\":\"" + value + "\",\"remark\":\"探针\"}");
        assertThat(r.path("code").asInt()).as(r.toString()).isZero();
        for (JsonNode i : r.path("data").path("items")) {
            if (value.equalsIgnoreCase(i.path("identityValue").asString())) return i.path("id").asLong();
        }
        throw new AssertionError("加了却不在列表里：" + r);
    }

    private void remove(String ops, Long id) throws Exception {
        call(post("/ops/test-identities/" + id + "/remove").header("Authorization", "Bearer " + ops), "{}");
    }

    private static String probePhone() {
        return "199" + String.format("%08d", System.nanoTime() % 100_000_000L);
    }

    private long otpLogCount(String phone) {
        String masked = phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM sys_notify_log WHERE channel = 'SMS' AND biz_type = 'OTP'"
                + " AND target = ?", Long.class, masked);
        return n == null ? 0 : n;
    }
}
