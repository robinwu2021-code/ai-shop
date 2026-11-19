package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code POST /common/appops/enrollment-ticket}: anonymous, configured apps only, strict Ed25519 SPKI, rate limited,
 * and the AppOps CLIENT token stays inside ai-shop. A fake AppOps on localhost checks what ai-shop sends.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("AppOps 安装票据：匿名、只发配置内 App、公钥严格、限流、CLIENT 令牌不出 ai-shop")
class AppOpsTicketHttpTest {

    static final HttpServer APPOPS;
    static final AtomicInteger TOKENS = new AtomicInteger();
    static final List<String> TICKET_REQUESTS = new CopyOnWriteArrayList<>();
    static volatile int ticketStatus = 201;

    static {
        try {
            APPOPS = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        APPOPS.createContext("/oauth2/token", ex -> {
            String basic = ex.getRequestHeaders().getFirst("Authorization");
            String expected = "Basic " + Base64.getEncoder().encodeToString("shop-client:s3cret".getBytes(StandardCharsets.UTF_8));
            byte[] body = expected.equals(basic)
                    ? ("{\"access_token\":\"cli-" + TOKENS.incrementAndGet() + "\",\"token_type\":\"Bearer\",\"expires_in\":600}")
                            .getBytes(StandardCharsets.UTF_8)
                    : "{}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(expected.equals(basic) ? 200 : 401, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        APPOPS.createContext("/api/appops/v1/internal/installations/enrollment-tickets", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            TICKET_REQUESTS.add(ex.getRequestHeaders().getFirst("Authorization") + "|"
                    + ex.getRequestHeaders().getFirst("Idempotency-Key") + "|" + body);
            byte[] out = (ticketStatus == 201
                    ? "{\"request_id\":\"r\",\"data\":{\"ticket_id\":\"t1\",\"ticket\":\"tk-secret\","
                            + "\"secret_available\":true,\"expires_at\":\"2026-11-19T03:05:00.000Z\"}}"
                    : "{\"request_id\":\"r\",\"error\":{\"code\":\"FORBIDDEN_ACTION\"}}").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(ticketStatus, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        APPOPS.start();
    }

    @DynamicPropertySource
    static void appops(DynamicPropertyRegistry r) {
        r.add("shop.appops.base-url", () -> "http://127.0.0.1:" + APPOPS.getAddress().getPort());
        r.add("shop.appops.client-id", () -> "shop-client");
        r.add("shop.appops.client-secret", () -> "s3cret");
        r.add("shop.appops.apps.nearboss.profile-id", () -> "prof-nb");
        r.add("shop.appops.apps.nearboss.distribution-id", () -> "dist-nb");
        r.add("shop.appops.tickets-per-ip-per-minute", () -> "4");
    }

    @AfterAll
    static void stop() {
        APPOPS.stop(0);
    }

    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper json;
    MockMvc mvc;
    String publicKey;

    @BeforeEach
    void setup() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
        publicKey = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic().getEncoded());
        ticketStatus = 201;
    }

    private MockHttpServletResponse ask(String app, String key, String ip) throws Exception {
        return mvc.perform(post("/common/appops/enrollment-ticket").contentType("application/json")
                        .content("{\"app\":\"" + app + "\",\"publicKey\":\"" + key + "\"}")
                        .with(req -> {
                            req.setRemoteAddr(ip);
                            return req;
                        }))
                .andReturn().getResponse();
    }

    private JsonNode body(MockHttpServletResponse r) throws Exception {
        return json.readTree(r.getContentAsString());
    }

    @Test
    @DisplayName("★★★ 配置内 App + 合法公钥 → 拿到票据；CLIENT 令牌复用、不回给客户端")
    void configured_app_gets_a_ticket_without_seeing_the_client_token() throws Exception {
        TICKET_REQUESTS.clear();
        MockHttpServletResponse first = ask("nearboss", publicKey, "203.0.113.10");
        assertThat(first.getStatus()).isEqualTo(200);
        assertThat(first.getHeader("Cache-Control")).contains("no-store");
        JsonNode r = body(first);
        assertThat(r.path("code").asInt()).isZero();
        assertThat(r.path("data").path("ticket").asString()).isEqualTo("tk-secret");
        assertThat(r.path("data").path("expiresAt").asString()).isEqualTo("2026-11-19T03:05:00.000Z");
        assertThat(first.getContentAsString()).doesNotContain("cli-");

        assertThat(ask("nearboss", publicKey, "203.0.113.10").getStatus()).isEqualTo(200);
        assertThat(TICKET_REQUESTS).hasSize(2);
        String[] sent = TICKET_REQUESTS.get(0).split("\\|", 3);
        assertThat(sent[0]).startsWith("Bearer cli-");
        assertThat(TICKET_REQUESTS.get(1).split("\\|", 3)[0]).isEqualTo(sent[0]);
        assertThat(sent[1]).matches("[0-9a-f-]{36}");
        assertThat(TICKET_REQUESTS.get(1).split("\\|", 3)[1]).isNotEqualTo(sent[1]);
        assertThat(json.readTree(sent[2]).path("profile_id").asString()).isEqualTo("prof-nb");
        assertThat(json.readTree(sent[2]).path("distribution_id").asString()).isEqualTo("dist-nb");
        assertThat(json.readTree(sent[2]).path("public_key").asString()).isEqualTo(publicKey);
    }

    @Test
    @DisplayName("★★ 未配置的 App、非 Ed25519 公钥 → 10400，且不打 AppOps")
    void unknown_app_and_bad_keys_are_refused_before_appops() throws Exception {
        TICKET_REQUESTS.clear();
        assertThat(body(ask("someapp", publicKey, "203.0.113.20")).path("code").asInt()).isEqualTo(10400);
        String rsa = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(KeyPairGenerator.getInstance("RSA").generateKeyPair().getPublic().getEncoded());
        assertThat(body(ask("nearboss", rsa, "203.0.113.20")).path("code").asInt()).isEqualTo(10400);
        assertThat(body(ask("nearboss", "A".repeat(59), "203.0.113.20")).path("code").asInt()).isEqualTo(10400);
        assertThat(TICKET_REQUESTS).isEmpty();
    }

    @Test
    @DisplayName("★★ 同一 IP 每分钟超过上限 → 10429；别的 IP 不受影响")
    void per_ip_rate_limit() throws Exception {
        for (int i = 0; i < 4; i++) {
            assertThat(body(ask("nearboss", publicKey, "203.0.113.30")).path("code").asInt()).isZero();
        }
        assertThat(body(ask("nearboss", publicKey, "203.0.113.30")).path("code").asInt()).isEqualTo(10429);
        assertThat(body(ask("nearboss", publicKey, "203.0.113.31")).path("code").asInt()).isZero();
    }

    @Test
    @DisplayName("★ AppOps 拒绝 → 10505，不透传 AppOps 的错误细节")
    void appops_refusal_is_dependency_unavailable() throws Exception {
        ticketStatus = 403;
        MockHttpServletResponse r = ask("nearboss", publicKey, "203.0.113.40");
        assertThat(body(r).path("code").asInt()).isEqualTo(10505);
        assertThat(r.getContentAsString()).doesNotContain("FORBIDDEN_ACTION");
    }
}
