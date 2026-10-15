package ai.neargo.shop.channel.notify.port;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class WxUrlLinkGatewayTest {
    @Test
    void trialLinkRequestTargetsRecognizedEntryAndPreservesToken() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> body = new AtomicReference<>();
        server.createContext("/cgi-bin/stable_token", exchange -> {
            byte[] reply = "{\"access_token\":\"local\",\"expires_in\":7200}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, reply.length);
            try (var out = exchange.getResponseBody()) { out.write(reply); }
        });
        server.createContext("/wxa/generate_urllink", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] reply = "{\"errcode\":0,\"url_link\":\"https://wxaurl.cn/test\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, reply.length);
            try (var out = exchange.getResponseBody()) { out.write(reply); }
        });
        server.start();
        try {
            WxUrlLinkGateway gateway = new WxUrlLinkGateway(
                    "http://127.0.0.1:" + server.getAddress().getPort(), "appid", "secret", 5, "trial");
            assertThat(gateway.generate("pages/home/index", "trackToken=abc.def"))
                    .contains("https://wxaurl.cn/test");
            var sent = new ObjectMapper().readTree(body.get());
            assertThat(sent.path("path").asText()).isEqualTo("pages/home/index");
            assertThat(sent.path("query").asText()).isEqualTo("trackToken=abc.def");
            assertThat(sent.path("env_version").asText()).isEqualTo("trial");
        } finally {
            server.stop(0);
        }
    }
}
