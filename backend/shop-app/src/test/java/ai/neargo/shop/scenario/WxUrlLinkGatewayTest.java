package ai.neargo.shop.scenario;

import ai.neargo.shop.channel.notify.port.WxUrlLinkGateway;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WxUrlLinkGatewayTest {
    private HttpServer server;
    private WxUrlLinkGateway gateway;
    private final AtomicReference<String> reply = new AtomicReference<>();

    @BeforeEach
    void startWechatEndpoint() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            String body = exchange.getRequestURI().getPath().contains("stable_token")
                    ? "{\"access_token\":\"fixture-token\",\"expires_in\":7200}" : reply.get();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        gateway = new WxUrlLinkGateway("http://127.0.0.1:" + server.getAddress().getPort(),
                "fixture-appid", "fixture-secret", 30);
    }

    @AfterEach
    void stopWechatEndpoint() {
        if (server != null) server.stop(0);
    }

    @Test
    void decodesEscapedSlashesBeforeReturningBrowserNavigationUrl() {
        reply.set("{\"url_link\":\"https:\\/\\/wxmpurl.cn\\/fixture\"}");
        assertThat(gateway.generate("pages/track/index", "t=fixture"))
                .contains("https://wxmpurl.cn/fixture");
    }

    @Test
    void acceptsUnescapedWechatLinks() {
        reply.set("{\"url_link\":\"https://wxmpurl.cn/fixture\"}");
        assertThat(gateway.generate("pages/track/index", "t=fixture"))
                .contains("https://wxmpurl.cn/fixture");
    }

    @Test
    void hidesUnavailableLinkWhenWechatReturnsAnError() {
        reply.set("{\"errcode\":40013,\"errmsg\":\"invalid appid\"}");
        assertThat(gateway.generate("pages/track/index", "t=fixture")).isEmpty();
    }
}
