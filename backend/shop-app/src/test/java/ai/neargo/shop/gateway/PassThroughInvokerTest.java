package ai.neargo.shop.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.gateway.core.proxy.PassThroughInvoker;
import ai.neargo.shop.gateway.core.resource.ApiResource;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 透传型转发（TDD-支付运营端经网关转发与权限下沉 §2.3、§6）：请求与响应原样过网关。
 * 下游是测试里起的桩服务，按路径回不同的东西并记下收到的请求。
 */
@DisplayName("网关 · 透传型转发")
class PassThroughInvokerTest {

    private static final AtomicReference<Map<String, String>> LAST = new AtomicReference<>();
    private static final AtomicReference<byte[]> LAST_BODY = new AtomicReference<>();
    private static final byte[] PDF = {'%', 'P', 'D', 'F', 0, 1, 2, (byte) 0xFF};
    private static final HttpServer STUB = startStub();
    private static final ApiResource ROUTE = PassThroughInvoker.route("/ops/pay-ops/v2/**", "PAY", "/pay-ops/v2/**");

    private final PassThroughInvoker invoker = new PassThroughInvoker(
            service -> Optional.of("http://127.0.0.1:" + STUB.getAddress().getPort()));

    private static HttpServer startStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                LAST.set(Map.of("method", exchange.getRequestMethod(), "uri", exchange.getRequestURI().toString(),
                        "auth", String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")),
                        "type", String.valueOf(exchange.getRequestHeaders().getFirst("Content-Type")),
                        "lang", String.valueOf(exchange.getRequestHeaders().getFirst("Accept-Language")),
                        "cookie", String.valueOf(exchange.getRequestHeaders().getFirst("Cookie"))));
                LAST_BODY.set(exchange.getRequestBody().readAllBytes());
                String path = exchange.getRequestURI().getPath();
                if (path.endsWith("/conflict")) {
                    reply(exchange, 409, "application/json", "{\"code\":40900,\"reasonCode\":\"VERSION_CONFLICT\",\"message\":\"版本已变\"}".getBytes(StandardCharsets.UTF_8));
                } else if (path.endsWith("/file")) {
                    exchange.getResponseHeaders().add("Content-Disposition", "inline; filename=\"a.pdf\"");
                    reply(exchange, 200, "application/pdf", PDF);
                } else {
                    reply(exchange, 200, "application/json", "{\"code\":0,\"data\":{\"ok\":true}}".getBytes(StandardCharsets.UTF_8));
                }
            });
            server.start();
            return server;
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void reply(com.sun.net.httpserver.HttpExchange exchange, int status, String type, byte[] body) throws java.io.IOException {
        exchange.getResponseHeaders().add("Content-Type", type);
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    @AfterAll
    static void stop() {
        STUB.stop(0);
    }

    @BeforeEach
    void reset() {
        LAST.set(null);
        LAST_BODY.set(null);
    }

    @Test
    @DisplayName("★★★ 方法、路径（保留编码）、查询串、Authorization、请求体字节原样到下游；Cookie 不转；包体不再包一层")
    void forwardsRequestAndResponseAsIs() throws Exception {
        var request = request("PUT", "/ops/pay-ops/v2/stores/S%201/collection-policy", "a=1&b=%E5%8F%B0");
        request.setContentType("application/json");
        request.addHeader("Authorization", "Bearer ops_token");
        request.addHeader("Accept-Language", "zh-TW");
        request.addHeader("Cookie", "s=secret");
        request.setContent("{\"mode\":\"OVERRIDE\"}".getBytes(StandardCharsets.UTF_8));
        var response = new MockHttpServletResponse();

        invoker.invoke(ROUTE, request, response, new MockFilterChain());

        assertThat(LAST.get()).containsEntry("method", "PUT")
                .containsEntry("uri", "/pay-ops/v2/stores/S%201/collection-policy?a=1&b=%E5%8F%B0")
                .containsEntry("auth", "Bearer ops_token").containsEntry("lang", "zh-TW").containsEntry("cookie", "null");
        assertThat(new String(LAST_BODY.get(), StandardCharsets.UTF_8)).isEqualTo("{\"mode\":\"OVERRIDE\"}");
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEqualTo("{\"code\":0,\"data\":{\"ok\":true}}");
    }

    @Test
    @DisplayName("★★★ 下游的 409 与 reasonCode 原样回给终端（前端靠它提示「版本已变」）")
    void downstreamErrorsPassThrough() throws Exception {
        var response = new MockHttpServletResponse();
        invoker.invoke(ROUTE, request("POST", "/ops/pay-ops/v2/channels/C1/conflict", null), response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(409);
        assertThat(response.getContentAsString(StandardCharsets.UTF_8)).contains("VERSION_CONFLICT");
    }

    @Test
    @DisplayName("★★ multipart 上传按字节转，下游拿到原 boundary；文件流与 Content-Disposition 原样回")
    void multipartAndBinary() throws Exception {
        byte[] multipart = ("--B\r\nContent-Disposition: form-data; name=\"file\"; filename=\"x.png\"\r\nContent-Type: image/png\r\n\r\n"
                + "\u0089PNG\r\n--B--\r\n").getBytes(StandardCharsets.ISO_8859_1);
        var upload = request("POST", "/ops/pay-ops/v2/onboardings/OB1/files", null);
        upload.setContentType("multipart/form-data; boundary=B");
        upload.setContent(multipart);
        invoker.invoke(ROUTE, upload, new MockHttpServletResponse(), new MockFilterChain());
        assertThat(LAST.get()).containsEntry("type", "multipart/form-data; boundary=B");
        assertThat(LAST_BODY.get()).isEqualTo(multipart);

        var response = new MockHttpServletResponse();
        invoker.invoke(ROUTE, request("GET", "/ops/pay-ops/v2/onboardings/OB1/files/F1/file", null), response, new MockFilterChain());
        assertThat(response.getContentType()).isEqualTo("application/pdf");
        assertThat(response.getHeader("Content-Disposition")).isEqualTo("inline; filename=\"a.pdf\"");
        assertThat(response.getContentAsByteArray()).isEqualTo(PDF);
    }

    @Test
    @DisplayName("★★ 下游连不上回 502，包体与 near-pay 错误同形（message + reasonCode）")
    void unreachableDownstream() throws Exception {
        var dead = new PassThroughInvoker(service -> Optional.of("http://127.0.0.1:1"));
        var response = new MockHttpServletResponse();
        dead.invoke(ROUTE, request("GET", "/ops/pay-ops/v2/orders", null), response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(502);
        assertThat(response.getContentAsString(StandardCharsets.UTF_8)).contains("\"reasonCode\":\"UPSTREAM_UNAVAILABLE\"").contains("\"message\"");
    }

    @Test
    @DisplayName("只接手透传路由，不会被选去转库里录入的微服务链接")
    void onlyHandlesPassThroughRoutes() {
        assertThat(invoker.supports(ROUTE)).isTrue();
        assertThat(invoker.supports(new ApiResource("GET", "/ops/x/y", "PAY", ai.neargo.shop.gateway.core.end.Access.LOGIN, null, "manual", "/internal/y"))).isFalse();
    }

    private static MockHttpServletRequest request(String method, String uri, String query) {
        var request = new MockHttpServletRequest(method, uri);
        request.setRequestURI(uri);
        request.setQueryString(query);
        return request;
    }
}
