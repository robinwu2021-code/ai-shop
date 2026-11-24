package ai.neargo.shop.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.TokenStore;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * 审批页面经网关透传到 OpsKit（TDD-OpsKit审批对接 AC2）：网关只认会话、不判权限码；令牌与请求体原样转发，
 * OpsKit 的状态码、错误体与 traceId / Idempotent-Replayed 原样回到页面。下游是桩服务。
 */
@SpringBootTest(properties = "shop.gateway.ops.opskit-proxy.enabled=true")
@ActiveProfiles("test")
@DisplayName("网关 · OpsKit 审批透传")
class OpsKitPassThroughTest {

    private static final List<String> RECEIVED = new CopyOnWriteArrayList<>();
    private static final HttpServer STUB = startStub();

    @Autowired
    WebApplicationContext context;
    @Autowired
    TokenStore tokenStore;

    @DynamicPropertySource
    static void target(DynamicPropertyRegistry registry) {
        registry.add("shop.services.targets.OPSKIT", () -> "http://127.0.0.1:" + STUB.getAddress().getPort());
    }

    private static HttpServer startStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                RECEIVED.add(exchange.getRequestMethod() + " " + exchange.getRequestURI() + " auth="
                        + exchange.getRequestHeaders().getFirst("Authorization") + " body=" + body);
                boolean decide = exchange.getRequestURI().getPath().endsWith("/decide");
                byte[] out = (decide
                        ? "{\"error\":\"opskit.approval.self_review\",\"message\":\"x\",\"traceId\":\"tr-1\"}"
                        : "{\"all\":3,\"process\":2,\"ticket\":1,\"claimable\":0}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.getResponseHeaders().add("traceId", "tr-1");
                exchange.getResponseHeaders().add("Idempotent-Replayed", "true");
                exchange.sendResponseHeaders(decide ? 403 : 200, out.length);
                exchange.getResponseBody().write(out);
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
    @DisplayName("★★★ 没登录在网关回 401，OpsKit 收不到请求")
    void anonymousStopsAtGateway() throws Exception {
        var response = mvc().perform(MockMvcRequestBuilders.post("/ops/opskit/v1/workspaces/ext:PLATFORM/todos/count")).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(RECEIVED).isEmpty();
    }

    @Test
    @DisplayName("★★★ 登录即放行：令牌与请求体原样到 OpsKit，响应头 traceId / Idempotent-Replayed 带回")
    void forwardedWithTokenBodyAndHeaders() throws Exception {
        String token = tokenStore.issue(TokenStore.SessionData.of(LoginUser.operator("OPSKIT-O", "o", List.of(), List.of())));
        var response = mvc().perform(MockMvcRequestBuilders.post("/ops/opskit/v1/workspaces/ext:PLATFORM/todos/count")
                .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content("{\"origin\":\"PROCESS\"}"))
                .andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEqualTo("{\"all\":3,\"process\":2,\"ticket\":1,\"claimable\":0}");
        assertThat(response.getHeader("traceId")).isEqualTo("tr-1");
        assertThat(response.getHeader("Idempotent-Replayed")).isEqualTo("true");
        assertThat(RECEIVED).containsExactly("POST /api/opskit/v1/workspaces/ext:PLATFORM/todos/count auth=Bearer " + token
                + " body={\"origin\":\"PROCESS\"}");
    }

    @Test
    @DisplayName("OpsKit 的规则拒绝（403 + 错误体）原样回到页面，不被网关改写")
    void downstreamErrorsPassThrough() throws Exception {
        String token = tokenStore.issue(TokenStore.SessionData.of(LoginUser.operator("OPSKIT-O", "o", List.of(), List.of())));
        var response = mvc().perform(MockMvcRequestBuilders.post("/ops/opskit/v1/workspaces/ext:PLATFORM/approvals/AP1/decide")
                .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("opskit.approval.self_review");
    }

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context).apply(SecurityMockMvcConfigurers.springSecurity()).build();
    }
}
