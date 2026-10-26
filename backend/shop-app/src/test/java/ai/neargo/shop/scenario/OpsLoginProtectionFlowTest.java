package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ai.neargo.shop.auth.PasswordHasher;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.notify.port.StubMailGateway;
import ai.neargo.shop.platform.OpsService;
import ai.neargo.shop.platform.impl.OpsSecondFactorGuard;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 运营登录防撞库（支付服务-安全规范 §2.7，G9）：同账号 15 分钟 5 次失败即锁、同 IP 15 分钟 20 次失败即限；
 * 第二因子 = 发到登录邮箱的一次性码（5 分钟、6 位、最多试 5 次、只能用一次）；生产 profile 不许关第二因子。
 * 测试世界默认关第二因子（testcfg），本类自己在共用上下文里打开再关回。每个用例用自己的账号与文档保留段 IP，
 * 不让锁定波及别的测试（全部测试都从 127.0.0.1 登录）。
 */
@SpringBootTest
@ActiveProfiles("test")
class OpsLoginProtectionFlowTest {

    private static final String RIGHT = "Right#2026-pass";

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PasswordHasher hasher;
    @Autowired private OpsService ops;
    @Autowired private StubMailGateway mail;
    @Autowired private ObjectMapper json;

    private MockMvc mvc;
    private String user;

    @BeforeEach
    void staff() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        String id = UUID.randomUUID().toString().substring(0, 8);
        user = "lg-" + id + "@neargo.ai";
        jdbc.update("INSERT INTO sys_ops_staff (staff_no, username, password, real_name, roles, status, created_at, updated_at)"
                + " SELECT ?, ?, ?, '登录防护', roles, 'ACTIVE', NOW(), NOW() FROM sys_ops_staff WHERE username='admin'",
                "ST-LG-" + id, user, hasher.encode(RIGHT));
    }

    @AfterEach
    void secondFactorOff() {
        secondFactor("off");
    }

    private JsonNode call(String path, String body, String ip) throws Exception {
        String r = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body)
                        .with(req -> {
                            req.setRemoteAddr(ip);
                            return req;
                        }))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(r);
    }

    private JsonNode login(String username, String password, String ip) throws Exception {
        return call("/ops/auth/login", "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}", ip);
    }

    private static int code(JsonNode r) {
        return r.get("code").asInt();
    }

    private static String token(JsonNode r) {
        JsonNode t = r.path("data").path("token");
        return t.isMissingNode() || t.isNull() ? null : t.asString();
    }

    @Test
    @DisplayName("★★★ 同账号 5 次失败即锁：第 6 次就算密码对也不放")
    void accountLocksAfterFiveFailures() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertThat(code(login(user, "wrong-" + i, "198.51.100.11"))).isEqualTo(ErrorCode.UNAUTHORIZED.code());
        }
        assertThat(code(login(user, RIGHT, "198.51.100.12"))).isEqualTo(ErrorCode.TOO_MANY_REQUESTS.code());
    }

    @Test
    @DisplayName("★★ 登录成功清零：输错几次后输对，失败计数不累积到下一轮")
    void successClearsFailures() throws Exception {
        for (int round = 0; round < 2; round++) {
            for (int i = 0; i < 4; i++) {
                login(user, "wrong-" + i, "198.51.100.21");
            }
            assertThat(token(login(user, RIGHT, "198.51.100.21"))).isNotBlank();
        }
    }

    @Test
    @DisplayName("★★★ 同 IP 换着账号撞（20 次失败）即限这个 IP；别的 IP 不受影响")
    void ipThrottledAcrossAccounts() throws Exception {
        String ip = "198.51.100." + (100 + (int) (Math.random() * 100));
        for (int i = 0; i < 20; i++) {
            login("nobody-" + UUID.randomUUID() + "@neargo.ai", "x", ip);
        }
        assertThat(code(login(user, RIGHT, ip))).isEqualTo(ErrorCode.TOO_MANY_REQUESTS.code());
        assertThat(token(login(user, RIGHT, "203.0.113.31"))).isNotBlank();
    }

    @Test
    @DisplayName("★★★ 第二因子：密码对只拿到挑战、没有令牌；邮箱里的 6 位码换令牌，且只能用一次")
    void secondFactorByEmail() throws Exception {
        secondFactor("email");

        JsonNode first = login(user, RIGHT, "198.51.100.41");
        assertThat(token(first)).as("密码对了也不该直接给令牌").isNull();
        String challenge = first.path("data").path("challenge").asString();
        assertThat(challenge).isNotBlank();
        assertThat(mail.last().to()).isEqualTo(user);
        Matcher m = Pattern.compile("\\b(\\d{6})\\b").matcher(mail.last().body());
        assertThat(m.find()).as("邮件里有 6 位码").isTrue();
        String otp = m.group(1);
        String wrong = otp.equals("000000") ? "111111" : "000000";

        assertThat(code(verify(challenge, wrong))).isEqualTo(ErrorCode.UNAUTHORIZED.code());
        assertThat(token(verify(challenge, otp))).isNotBlank();
        assertThat(code(verify(challenge, otp))).as("用过一次就作废").isEqualTo(ErrorCode.UNAUTHORIZED.code());
    }

    @Test
    @DisplayName("★★★ 猜码：同一挑战错 5 次即作废，之后给对的码也不行")
    void challengeDiesAfterFiveWrongCodes() throws Exception {
        secondFactor("email");
        String challenge = login(user, RIGHT, "198.51.100.51").path("data").path("challenge").asString();
        Matcher m = Pattern.compile("\\b(\\d{6})\\b").matcher(mail.last().body());
        assertThat(m.find()).isTrue();
        String otp = m.group(1);
        String wrong = otp.equals("000000") ? "111111" : "000000";
        for (int i = 0; i < 5; i++) {
            verify(challenge, wrong);
        }
        assertThat(token(verify(challenge, otp))).isNull();
    }

    @Test
    @DisplayName("★★★ 生产 profile 关第二因子 = 启动失败；非生产随意")
    void prodRefusesSecondFactorOff() {
        assertThatThrownBy(() -> OpsSecondFactorGuard.check(List.of("prod", "api", "ops"), "off"))
                .isInstanceOf(IllegalStateException.class);
        OpsSecondFactorGuard.check(List.of("prod", "ops"), "email");
        OpsSecondFactorGuard.check(List.of("test", "ops"), "off");
    }

    /** 共用上下文里的 OpsServiceImpl 是代理：改字段要改到目标对象上 */
    private void secondFactor(String mode) {
        Object target = AopTestUtils.getTargetObject(ops);
        ReflectionTestUtils.setField(target, "secondFactor", mode);
    }

    private JsonNode verify(String challenge, String code) throws Exception {
        return call("/ops/auth/login/verify", "{\"challenge\":\"" + challenge + "\",\"code\":\"" + code + "\"}",
                "198.51.100.61");
    }
}
