package ai.neargo.shop.channel.ai.port;

import ai.neargo.shop.auth.BizContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;

import static org.assertj.core.api.Assertions.assertThat;

class GoodsEditorVisionGatewayTest {
    private static final String SETTINGS = "shop.ai.goods.editor.";
    private static final ObjectMapper JSON = new ObjectMapper();
    private final LinkedBlockingQueue<Request> requests = new LinkedBlockingQueue<>();
    private HttpServer server;

    @AfterEach
    void stop() {
        BizContext.clear();
        if (server != null) server.stop(0);
    }

    @Test
    void relaySendsTextAndImageOnlyForTheConfiguredTestStore() throws Exception {
        var gateway = gateway("relay",
                "{\"choices\":[{\"message\":{\"content\":\"{\\\"name\\\":\\\"MANGO\\\",\\\"title\\\":\\\"MANGO\\\",\\\"confidence\\\":1}\"}}]}");
        selectStore("another-shop");
        assertThat(gateway.extractText("MANGO")).isNull();
        assertThat(gateway.recognize("https://example.test/image.png", Map.of())).isNull();
        assertThat(requests).isEmpty();

        selectStore("shop-under-test");
        assertThat(gateway.extractText("MANGO").name()).isEqualTo("MANGO");
        assertThat(gateway.recognize("https://example.test/image.png", Map.of()).title()).isEqualTo("MANGO");
        var text = requests.remove();
        var image = requests.remove();
        assertThat(text.path()).isEqualTo("/v1/chat/completions");
        assertThat(text.braId()).isEqualTo("platform-test-branch");
        assertThat(text.body().path("messages").path(0).path("content").asText()).contains("MANGO");
        assertThat(text.body().has("chat_template_kwargs")).isFalse();
        assertThat(image.body().path("max_tokens").asInt()).isEqualTo(2048);
        assertThat(image.body().path("messages").path(0).path("content").path(1)
                .path("image_url").path("url").asText()).isEqualTo("https://example.test/image.png");
    }

    @Test
    void switchingToOpenAiUsesResponsesAndDoesNotSendTheRelayStore() throws Exception {
        var gateway = gateway("openai", """
                {"status":"completed","output":[{"type":"message","content":[
                  {"type":"output_text","text":"{\\"name\\":\\"MANGO\\",\\"title\\":\\"MANGO\\",\\"confidence\\":1}"}
                ]}]}
                """);
        selectStore("shop-under-test");
        assertThat(gateway.extractText("MANGO").name()).isEqualTo("MANGO");
        assertThat(gateway.recognize("https://example.test/image.png", Map.of()).title()).isEqualTo("MANGO");
        var text = requests.remove();
        var image = requests.remove();
        assertThat(text.path()).isEqualTo("/v1/responses");
        assertThat(text.braId()).isNull();
        assertThat(text.authorization()).isEqualTo("Bearer test-key");
        assertThat(text.body().path("store").asBoolean()).isFalse();
        assertThat(image.body().path("input").path(0).path("content").path(1).path("type").asText())
                .isEqualTo("input_image");
    }

    private GoodsVisionGateway gateway(String provider, String response) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/", exchange -> {
            requests.add(new Request(exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("X-Bra-Id"),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    JSON.readTree(exchange.getRequestBody())));
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        var env = new MockEnvironment()
                .withProperty(SETTINGS + "provider", provider)
                .withProperty(SETTINGS + "test-store-no", "shop-under-test")
                .withProperty(SETTINGS + "relay.test-bra-id", "platform-test-branch")
                .withProperty(SETTINGS + provider + ".base-url", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1")
                .withProperty(SETTINGS + provider + ".model", "test-model")
                .withProperty(SETTINGS + provider + ".api-key", "test-key");
        return new GoodsVisionGateway("", "", "", 25, false, env);
    }

    private static void selectStore(String store) {
        BizContext.set(new BizContext("merchant", Set.of(), Set.of(), Set.of(store), store, true));
    }

    private record Request(String path, String braId, String authorization, JsonNode body) {}
}
