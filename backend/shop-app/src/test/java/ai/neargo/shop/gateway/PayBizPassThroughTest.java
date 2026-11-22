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
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * B 端支付接口经网关透传（TDD-支付三端经网关透传与权限归属 §3.1、§6）：网关只认商户令牌，不判权限码，
 * 请求原样转给支付服务，客户端带来的内部头不转。下游是测试里起的桩服务，代替 near-pay。
 */
@SpringBootTest(properties = "shop.gateway.biz.pay-proxy.enabled=true")
@ActiveProfiles("test")
@DisplayName("网关 · B 端支付接口透传")
class PayBizPassThroughTest {

    private static final List<String> RECEIVED = new CopyOnWriteArrayList<>();
    private static final HttpServer STUB = startStub();

    @Autowired
    WebApplicationContext context;
    @Autowired
    TokenStore tokenStore;

    @DynamicPropertySource
    static void target(DynamicPropertyRegistry registry) {
        registry.add("shop.services.targets.PAY", () -> "http://127.0.0.1:" + STUB.getAddress().getPort());
    }

    private static HttpServer startStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                var headers = exchange.getRequestHeaders();
                RECEIVED.add(exchange.getRequestMethod() + " " + exchange.getRequestURI()
                        + " auth=" + headers.getFirst("Authorization")
                        + " merchant=" + headers.getFirst("X-Merchant-No")
                        + " internal=" + headers.getFirst("X-Internal-Caller")
                        + " body=" + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] body = "{\"code\":40300,\"reasonCode\":\"NOT_MEMBER\",\"data\":null}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(403, body.length);
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
        var response = mvc().perform(MockMvcRequestBuilders.get("/biz/pay/v2/memberships")).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(RECEIVED).isEmpty();
    }

    @Test
    @DisplayName("★★★ 运营端令牌打 B 端支付前缀：401，不转发")
    void otherEndTokenRejected() throws Exception {
        String operator = tokenStore.issue(TokenStore.SessionData.of(LoginUser.operator("PB-O", "o", List.of(), List.of("*"))));
        var response = mvc().perform(MockMvcRequestBuilders.get("/biz/pay/v2/memberships")
                .header("Authorization", "Bearer " + operator)).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(RECEIVED).isEmpty();
    }

    @Test
    @DisplayName("★★★ 商户令牌：方法、路径、查询串、令牌、App 选的商户号、请求体原样转；下游的 403 与包体原样回；伪造的内部头被去掉")
    void merchantForwardedVerbatim() throws Exception {
        String token = tokenStore.issue(TokenStore.SessionData.of(LoginUser.merchant("PB-M-1", "m")));
        var response = mvc().perform(MockMvcRequestBuilders.post("/biz/pay/v2/onboarding/applications").queryParam("lang", "zh")
                .header("Authorization", "Bearer " + token)
                .header("X-Merchant-No", "PM-APP")
                .header("X-Internal-Caller", "forged")
                .contentType("application/json").content("{\"a\":1}")).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).isEqualTo("{\"code\":40300,\"reasonCode\":\"NOT_MEMBER\",\"data\":null}");
        assertThat(RECEIVED).containsExactly(
                "POST /biz/pay/v2/onboarding/applications?lang=zh auth=Bearer " + token + " merchant=PM-APP internal=null body={\"a\":1}");
    }

    @Test
    @DisplayName("前缀之外的 B 端地址不受影响")
    void otherBizPathsUntouched() throws Exception {
        String token = tokenStore.issue(TokenStore.SessionData.of(LoginUser.merchant("PB-M-2", "m")));
        mvc().perform(MockMvcRequestBuilders.get("/biz/pay/v2x/memberships").header("Authorization", "Bearer " + token)).andReturn();
        mvc().perform(MockMvcRequestBuilders.get("/biz/payment/orders").header("Authorization", "Bearer " + token)).andReturn();
        assertThat(RECEIVED).isEmpty();
    }

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context).apply(SecurityMockMvcConfigurers.springSecurity()).build();
    }
}
