package ai.neargo.svc.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

/**
 * 真实 socket 上的服务间调用：本地起一个 JDK {@link HttpServer}，不用 MockRestServiceServer ——
 * 替身不经过 JDK HttpClient，协议版本、超时、连接拒绝这几样它根本测不到。
 */
@DisplayName("服务间调用的传输层")
class ServiceClientsTest {

    record Rule(String code, int rateBp) {
    }

    @HttpExchange("/internal/demo")
    interface DemoApi {
        @GetExchange("/rules")
        List<Rule> rules(@RequestParam("at") long at);

        @PostExchange("/rules/{code}/touch")
        Rule touch(@PathVariable("code") String code, @RequestBody Rule body);

        @GetExchange("/slow")
        String slow();
    }

    /** 服务端看到的每一个请求：方法 路径?查询 协议 令牌 */
    private final List<String> seen = new CopyOnWriteArrayList<>();
    private final List<HttpServer> servers = new CopyOnWriteArrayList<>();

    @AfterEach
    void stop() {
        servers.forEach(s -> s.stop(0));
    }

    private String startServer(int status, String json) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            seen.add(ex.getRequestMethod() + " " + ex.getRequestURI() + " " + ex.getProtocol()
                    + " token=" + ex.getRequestHeaders().getFirst("X-Test-Token")
                    // 没锁 HTTP/1.1 时，JDK HttpClient 会在明文请求上带 Upgrade: h2c 试着升级 ——
                    // 服务端不支持时协议照样是 HTTP/1.1，所以「协议版本」这个量证伪不了，要看这个头
                    + (ex.getRequestHeaders().containsKey("Upgrade") ? " UPGRADE" : ""));
            if (ex.getRequestURI().getPath().endsWith("/slow")) {
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(status, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        server.start();
        servers.add(server);
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static ServiceClientSpec spec(AtomicReference<String> base, String token, boolean requireToken) {
        return new ServiceClientSpec("DEMO", name -> Optional.ofNullable(base.get()), "X-Test-Token",
                () -> token, requireToken, Duration.ofSeconds(1), Duration.ofMillis(500));
    }

    @Test
    @DisplayName("★★★ 成功：路径 / 查询串 / 令牌头都到了对方，锁在 HTTP/1.1（不试 h2c 升级），应答解成对象")
    void okRoundTrip() throws IOException {
        AtomicReference<String> base = new AtomicReference<>(startServer(200, "[{\"code\":\"A\",\"rateBp\":30}]"));
        DemoApi api = ServiceClients.create(DemoApi.class, spec(base, "s3cret", true));

        List<Rule> rules = ServiceCalls.call("DEMO", () -> api.rules(42));

        assertThat(rules).containsExactly(new Rule("A", 30));
        assertThat(seen).containsExactly("GET /internal/demo/rules?at=42 HTTP/1.1 token=s3cret");
    }

    @Test
    @DisplayName("★★★ 没配地址：NOT_CONFIGURED，而且请求根本没发出去")
    void notConfigured() {
        AtomicReference<String> base = new AtomicReference<>(null);
        DemoApi api = ServiceClients.create(DemoApi.class, spec(base, "s3cret", true));

        assertThatThrownBy(() -> ServiceCalls.call("DEMO", () -> api.rules(1)))
                .isInstanceOfSatisfying(ServiceCallException.class,
                        e -> assertThat(e.outcome()).isEqualTo(CallOutcome.NOT_CONFIGURED));
        assertThat(seen).isEmpty();
    }

    @Test
    @DisplayName("★★★ 令牌必填而没配：NOT_CONFIGURED，不发请求 —— 「没配就不校验」等于内部口对任何人开放")
    void missingRequiredToken() throws IOException {
        AtomicReference<String> base = new AtomicReference<>(startServer(200, "[]"));
        DemoApi api = ServiceClients.create(DemoApi.class, spec(base, "", true));

        assertThatThrownBy(() -> ServiceCalls.call("DEMO", () -> api.rules(1)))
                .isInstanceOfSatisfying(ServiceCallException.class,
                        e -> assertThat(e.outcome()).isEqualTo(CallOutcome.NOT_CONFIGURED));
        assertThat(seen).isEmpty();
    }

    @Test
    @DisplayName("★★ 令牌非必填且为空：照样发，头的值为空（job 侧今天的行为）")
    void optionalTokenStillSends() throws IOException {
        AtomicReference<String> base = new AtomicReference<>(startServer(200, "[]"));
        DemoApi api = ServiceClients.create(DemoApi.class, spec(base, null, false));

        ServiceCalls.call("DEMO", () -> api.rules(1));

        assertThat(seen).containsExactly("GET /internal/demo/rules?at=1 HTTP/1.1 token=");
    }

    @Test
    @DisplayName("★★★ 对方回非 2xx：REMOTE_ERROR，带上状态码（job 侧要靠 409 / 404 分辨跳过与缺 handler）")
    void remoteErrorKeepsStatus() throws IOException {
        AtomicReference<String> base = new AtomicReference<>(startServer(409, "{\"msg\":\"locked\"}"));
        DemoApi api = ServiceClients.create(DemoApi.class, spec(base, "s3cret", true));

        assertThatThrownBy(() -> ServiceCalls.call("DEMO", () -> api.touch("A", new Rule("A", 1))))
                .isInstanceOfSatisfying(ServiceCallException.class, e -> {
                    assertThat(e.outcome()).isEqualTo(CallOutcome.REMOTE_ERROR);
                    assertThat(e.statusCode()).isEqualTo(409);
                    assertThat(e.getMessage()).as("消息里不带应答体").doesNotContain("locked");
                });
        assertThat(seen).containsExactly("POST /internal/demo/rules/A/touch HTTP/1.1 token=s3cret");
    }

    @Test
    @DisplayName("★★ 200 但应答读不了：REMOTE_ERROR，不当成功")
    void unreadableBodyIsRemoteError() throws IOException {
        AtomicReference<String> base = new AtomicReference<>(startServer(200, "<html>not json</html>"));
        DemoApi api = ServiceClients.create(DemoApi.class, spec(base, "s3cret", true));

        assertThatThrownBy(() -> ServiceCalls.call("DEMO", () -> api.rules(1)))
                .isInstanceOfSatisfying(ServiceCallException.class,
                        e -> assertThat(e.outcome()).isEqualTo(CallOutcome.REMOTE_ERROR));
    }

    @Test
    @DisplayName("★★★ 对方慢过读超时：TIMEOUT")
    void timeout() throws IOException {
        AtomicReference<String> base = new AtomicReference<>(startServer(200, "\"late\""));
        DemoApi api = ServiceClients.create(DemoApi.class, spec(base, "s3cret", true));

        assertThatThrownBy(() -> ServiceCalls.call("DEMO", api::slow))
                .isInstanceOfSatisfying(ServiceCallException.class,
                        e -> assertThat(e.outcome()).isEqualTo(CallOutcome.TIMEOUT));
    }

    @Test
    @DisplayName("★★★ 对方没起：UNREACHABLE —— 与「没配地址」分开")
    void unreachable() throws IOException {
        int closedPort;
        try (ServerSocket s = new ServerSocket(0)) {
            closedPort = s.getLocalPort();
        }
        AtomicReference<String> base = new AtomicReference<>("http://127.0.0.1:" + closedPort);
        DemoApi api = ServiceClients.create(DemoApi.class, spec(base, "s3cret", true));

        assertThatThrownBy(() -> ServiceCalls.call("DEMO", () -> api.rules(1)))
                .isInstanceOfSatisfying(ServiceCallException.class,
                        e -> assertThat(e.outcome()).isEqualTo(CallOutcome.UNREACHABLE));
    }

    @Test
    @DisplayName("★★ 地址每次调用现查：换了地址，下一次就打到新地址上（将来换服务发现靠的就是这一条）")
    void resolvesBaseUrlPerCall() throws IOException {
        String first = startServer(200, "[]");
        String second = startServer(200, "[{\"code\":\"B\",\"rateBp\":5}]");
        AtomicReference<String> base = new AtomicReference<>(first + "/");
        DemoApi api = ServiceClients.create(DemoApi.class, spec(base, "s3cret", true));

        assertThat(ServiceCalls.call("DEMO", () -> api.rules(1))).isEmpty();
        base.set(second);
        assertThat(ServiceCalls.call("DEMO", () -> api.rules(2))).containsExactly(new Rule("B", 5));
        assertThat(seen).as("尾斜杠被去掉，没有拼出 //internal").allMatch(line -> !line.contains("//internal"));
    }
}
