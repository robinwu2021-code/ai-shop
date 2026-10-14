package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 密钥票据免登录的真实链路（ADR-027）：真 Tomcat、真安全过滤器链、真会话存储。
 *
 * <p>要证的是「换出来的会话<b>真能用</b>」—— 只断言拿到了一个字符串的话，
 * 签了一个链路不认的令牌也会绿。
 */
@DisplayName("密钥票据免登录：换出的会话能调店主与运营接口")
class AutomationLoginFlowTest {

    static final KeyPair KEYS;

    static {
        try {
            KEYS = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static final ObjectMapper JSON = new ObjectMapper();
    static final HttpClient HTTP = HttpClient.newHttpClient();

    static String ticket(String realm, String sub) throws Exception {
        long exp = System.currentTimeMillis() / 1000 + 50;
        String json = "{\"realm\":\"" + realm + "\",\"sub\":\"" + sub + "\",\"exp\":" + exp
                + ",\"nonce\":\"" + UUID.randomUUID() + "\"}";
        String p = Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        Signature s = Signature.getInstance("Ed25519");
        s.initSign(KEYS.getPrivate());
        s.update(("v1." + p).getBytes(StandardCharsets.US_ASCII));
        return "v1." + p + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(s.sign());
    }

    static HttpResponse<String> post(int port, String path, String body) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    static JsonNode get(int port, String path, String token) throws Exception {
        String b = HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString()).body();
        return JSON.readTree(b);
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @ActiveProfiles({"test", "ops"})
    @TestPropertySource(properties = {
            "spring.datasource.url=jdbc:h2:mem:automation;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            "shop.auth.automation.enabled=true",
            "shop.auth.automation.subjects=B:U-AUTOTEST,OPS:ST-SUPPORT",
    })
    @DisplayName("开关开着")
    class Enabled {

        @LocalServerPort int port;
        @org.springframework.beans.factory.annotation.Autowired
        org.springframework.jdbc.core.JdbcTemplate jdbc;

        /** 这个类独占一个内存库（上面的 datasource.url），建的测试账号不会漏到别的用例里 */
        @org.junit.jupiter.api.BeforeEach
        void user() {
            jdbc.update("INSERT INTO usr_account (user_no, nickname, created_at, updated_at) "
                    + "SELECT 'U-AUTOTEST', '自动化测试', NOW(), NOW() FROM DUAL "
                    + "WHERE NOT EXISTS (SELECT 1 FROM usr_account WHERE user_no = 'U-AUTOTEST')");
        }

        @DynamicPropertySource
        static void key(DynamicPropertyRegistry r) {
            r.add("shop.auth.automation.public-key",
                    () -> Base64.getEncoder().encodeToString(KEYS.getPublic().getEncoded()));
        }

        private JsonNode exchange(String ticket) throws Exception {
            return JSON.readTree(post(port, "/common/auth/automation", "{\"ticket\":\"" + ticket + "\"}").body());
        }

        @Test
        @DisplayName("★★★ 店主票据 → btk_，能调 /biz 接口")
        void merchantTicketGivesWorkingBizSession() throws Exception {
            JsonNode r = exchange(ticket("B", "U-AUTOTEST"));
            String token = r.path("data").path("token").asString();

            assertThat(token).startsWith("btk_");
            assertThat(get(port, "/biz/merchant/profile", token).path("code").asInt()).isZero();
        }

        @Test
        @DisplayName("★★★ 运营票据 → otk_，能调 /ops 接口，身份就是那个员工")
        void opsTicketGivesWorkingOpsSession() throws Exception {
            String token = exchange(ticket("OPS", "ST-SUPPORT")).path("data").path("token").asString();

            assertThat(token).startsWith("otk_");
            JsonNode me = get(port, "/ops/auth/me", token);
            assertThat(me.path("code").asInt()).isZero();
            assertThat(me.path("data").toString()).contains("ST-SUPPORT");
        }

        @Test
        @DisplayName("★★★ 白名单外的账号、重放的票据 → 拿不到令牌")
        void rejectedTicketsGiveNoToken() throws Exception {
            assertThat(exchange(ticket("OPS", "ST-ADMIN")).path("data").path("token").isMissingNode()
                    || exchange(ticket("OPS", "ST-ADMIN")).path("data").isNull()).isTrue();

            String t = ticket("B", "U-AUTOTEST");
            assertThat(exchange(t).path("data").path("token").asString()).startsWith("btk_");
            JsonNode again = exchange(t);
            assertThat(again.path("code").asInt()).isNotZero();
            assertThat(again.path("data").path("token").isMissingNode() || again.path("data").isNull()).isTrue();
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @ActiveProfiles("test")
    @TestPropertySource(properties = {
            "spring.datasource.url=jdbc:h2:mem:automationoff;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    })
    @DisplayName("开关关着（默认）")
    class Disabled {

        @LocalServerPort int port;

        @Test
        @DisplayName("★★★ 默认关：404，与接口不存在无法区分")
        void disabledIs404() throws Exception {
            assertThat(post(port, "/common/auth/automation", "{\"ticket\":\"" + ticket("B", "U-AUTOTEST") + "\"}")
                    .statusCode()).isEqualTo(404);
        }
    }
}
