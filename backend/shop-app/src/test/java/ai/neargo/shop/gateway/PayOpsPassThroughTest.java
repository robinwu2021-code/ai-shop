package ai.neargo.shop.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.auth.store.TokenHash;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.TokenStore;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * 支付运营接口经网关透传（TDD-支付运营端经网关转发与权限下沉 §2.3、§6）：网关只校验登录，不判权限码；令牌原样带给支付服务。
 * 下游是测试里起的桩服务，代替 near-pay。
 */
@SpringBootTest(properties = "shop.gateway.ops.pay-ops-proxy.enabled=true")
@ActiveProfiles("test")
@DisplayName("网关 · 支付运营接口透传")
class PayOpsPassThroughTest {

    private static final List<String> RECEIVED = new CopyOnWriteArrayList<>();
    private static final HttpServer STUB = startStub();

    @Autowired
    WebApplicationContext context;
    @Autowired
    TokenStore tokenStore;
    @Autowired
    JdbcTemplate jdbc;

    @DynamicPropertySource
    static void target(DynamicPropertyRegistry registry) {
        registry.add("shop.services.targets.PAY", () -> "http://127.0.0.1:" + STUB.getAddress().getPort());
    }

    private static HttpServer startStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                RECEIVED.add(exchange.getRequestMethod() + " " + exchange.getRequestURI()
                        + " auth=" + exchange.getRequestHeaders().getFirst("Authorization"));
                byte[] body = "{\"code\":0,\"data\":{\"records\":[],\"total\":0}}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterAll
    static void stop() {
        STUB.stop(0);
    }

    @BeforeEach
    void reset() {
        RECEIVED.clear();
    }

    @Test
    @DisplayName("★★★ 没登录在网关回 401，支付服务收不到请求")
    void anonymousStopsAtGateway() throws Exception {
        var response = mvc().perform(MockMvcRequestBuilders.get("/ops/pay-ops/v2/merchants")).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(RECEIVED).isEmpty();
    }

    @Test
    @DisplayName("★★★ 登录了但没有任何支付权限码，网关照样放行并带上原令牌 —— 判权在支付服务里")
    void loggedInIsForwardedWithTokenWithoutPermissionCheck() throws Exception {
        String token = tokenStore.issue(TokenStore.SessionData.of(LoginUser.operator("PASS-O", "pass", List.of(), List.of())));
        var response = mvc().perform(MockMvcRequestBuilders.get("/ops/pay-ops/v2/merchants").queryParam("page", "1")
                .header("Authorization", "Bearer " + token)).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEqualTo("{\"code\":0,\"data\":{\"records\":[],\"total\":0}}");
        assertThat(RECEIVED).containsExactly("GET /pay-ops/v2/merchants?page=1 auth=Bearer " + token);
    }

    @Test
    @DisplayName("前缀之外的运营端地址不受影响")
    void otherOpsPathsUntouched() throws Exception {
        mvc().perform(MockMvcRequestBuilders.get("/ops/pay-ops/v2x/merchants")).andReturn();
        assertThat(RECEIVED).isEmpty();
    }

    @Test
    void employeeKeyNeverReachesDownstream() throws Exception {
        String key = "oak_" + "a".repeat(43);
        long now = System.currentTimeMillis();
        jdbc.update("""
                INSERT INTO sys_ops_api_key (key_no,staff_no,secret_hash,label,access_level,issued_by,created_at,expires_at)
                SELECT ?, staff_no, ?, 'gateway test', 'MANAGE', staff_no, ?, ? FROM sys_ops_staff WHERE username='admin'
                """, UUID.randomUUID().toString(), TokenHash.of(key), now, now + 60_000);
        var response = mvc().perform(MockMvcRequestBuilders.get("/ops/pay-ops/v2/merchants")
                .header("Authorization", "Bearer " + key)).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(RECEIVED).isEmpty();
    }

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context).apply(SecurityMockMvcConfigurers.springSecurity()).build();
    }
}
