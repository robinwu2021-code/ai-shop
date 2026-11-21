package ai.neargo.inventory.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.ClientHttpRequestInterceptor;

/** 调用方挂的拦截器（如 ai-shop 网关的签名上下文）随每个调用发出去，凭证头照旧。 */
@DisplayName("库存客户端 · 调用方的拦截器")
class InventoryClientInterceptorTest {

    /** 只看请求有没有带上头；桩服务的响应体不按真契约给，客户端解析失败与本测试无关 */
    private static void call(Runnable request) {
        try {
            request.run();
        } catch (RuntimeException ignored) {
            // 见上
        }
    }

    @Test
    @DisplayName("★★ 拦截器加的头到了服务端；不挂拦截器的老写法照旧")
    void interceptorHeadersReachTheServer() throws Exception {
        AtomicReference<String> marker = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            marker.set(exchange.getRequestHeaders().getFirst("X-Gateway-Context"));
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = "{\"code\":0,\"data\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            ClientHttpRequestInterceptor sign = (request, body, execution) -> {
                request.getHeaders().set("X-Gateway-Context", "ctx-1");
                return execution.execute(request, body);
            };
            InventoryClient signed = InventoryClient.create(() -> Optional.of(base), "app", "secret",
                    Duration.ofSeconds(2), Duration.ofSeconds(2), 5, Duration.ofSeconds(10), "test.inventory",
                    List.of(sign));
            call(() -> signed.self("OP-1").warehouses(null));
            assertThat(marker.get()).isEqualTo("ctx-1");
            assertThat(auth.get()).startsWith("Bearer app:");

            marker.set(null);
            InventoryClient plain = InventoryClient.create(() -> Optional.of(base), "app", "secret",
                    Duration.ofSeconds(2), Duration.ofSeconds(2), 5, Duration.ofSeconds(10), "test.inventory");
            call(() -> plain.self("OP-1").warehouses(null));
            assertThat(marker.get()).isNull();
        } finally {
            server.stop(0);
        }
    }
}
