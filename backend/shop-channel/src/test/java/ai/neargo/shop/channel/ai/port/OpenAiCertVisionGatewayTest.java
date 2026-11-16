package ai.neargo.shop.channel.ai.port;

import ai.neargo.shop.spi.product.CertVisionPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiCertVisionGatewayTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void imageAndPdfUseResponses() throws Exception {
        var request = new AtomicReference<JsonNode>();
        var authorization = new AtomicReference<String>();
        var reply = new AtomicReference<String>(response("{\"documentType\":\"BANK_COVER\",\"confidence\":0.9,\"fields\":{\"bankAccount\":\"001234567890\"}}"));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            request.set(JSON.readTree(exchange.getRequestBody()));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = reply.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        try {
            var gateway = new OpenAiCertVisionGateway("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/", "vision-model", "test-key", 3, true);
            var result = gateway.recognizeDocument(new byte[]{1, 2, 3}, "image/png", CertVisionPort.DocumentType.BANK_COVER);
            assertThat(result.fields()).containsEntry("bankAccount", "001234567890");
            assertThat(authorization.get()).isEqualTo("Bearer test-key");
            var sent = request.get();
            assertThat(sent.path("store").asBoolean()).isFalse();
            assertThat(sent.has("chat_template_kwargs")).isFalse();
            assertThat(sent.path("input").get(0).path("content").get(0).path("text").asText()).contains("實收資本額", "資本總額或登記資本不得代替");
            assertThat(sent.path("text").path("format").path("strict").asBoolean()).isTrue();
            assertThat(sent.path("input").get(0).path("content").get(1).path("image_url").asText()).isEqualTo("data:image/png;base64,AQID");
            var schema = sent.path("text").path("format").path("schema").path("properties").path("fields");
            assertThat(schema.path("properties").has("identityNo")).isFalse();
            assertThat(schema.path("properties").has("bankAccount")).isTrue();
            gateway.recognizeDocument(new byte[]{4, 5}, "application/pdf", CertVisionPort.DocumentType.BANK_COVER);
            var file = request.get().path("input").get(0).path("content").get(1);
            assertThat(file.path("type").asText()).isEqualTo("input_file");
            assertThat(file.path("filename").asText()).isEqualTo("document.pdf");
            assertThat(file.path("file_data").asText()).isEqualTo("data:application/pdf;base64,BAU=");
        } finally { server.stop(0); }
    }

    @Test void entityTypesUseOwnFields() throws Exception {
        var result = OpenAiCertVisionGateway.documentResult(JSON.readTree("{\"documentType\":\"ENTITY_REGISTRATION\",\"confidence\":0.9,"
                + "\"fields\":{\"registeredCity\":\"臺北市\",\"legalForm\":\"ENTERPRISE\",\"bankAccount\":\"1\"}}"), CertVisionPort.DocumentType.ENTITY_REGISTRATION);
        assertThat(result.fields()).containsOnlyKeys("registeredCity", "legalForm");
        assertThat(CertVisionPort.DocumentType.ARC_BACK.fields()).containsExactly("arcBackNo");
        assertThat(CertVisionPort.DocumentType.REGISTRATION.fields()).doesNotContain("registeredCity");
    }

    @Test void documentFieldsAreRestrictedToSelectedType() throws Exception {
        assertThat(OpenAiCertVisionGateway.documentResult(JSON.readTree("{\"documentType\":\"BANK_COVER\",\"confidence\":0.7,\"fields\":{\"bankAccount\":\"000123\",\"identityNo\":\"OTHER\"}}"), CertVisionPort.DocumentType.BANK_COVER).fields())
                .containsExactly(Map.entry("bankAccount", "000123"));
        assertThat(OpenAiCertVisionGateway.documentResult(JSON.readTree("{\"documentType\":\"OWNER_FRONT\",\"confidence\":1,\"fields\":{}}"), CertVisionPort.DocumentType.BANK_COVER)).isNull();
        assertThat(OpenAiCertVisionGateway.documentResult(JSON.readTree("{\"documentType\":\"BANK_COVER\",\"confidence\":1,\"fields\":{\"bankAccount\":123}}"), CertVisionPort.DocumentType.BANK_COVER)).isNull();
    }

    @Test void unusableResponsesReturnNoRecognition() throws Exception {
        for (String body : List.of("{}", "{\"status\":\"incomplete\",\"output\":[]}", "{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"refusal\",\"refusal\":\"not available\"}]}]}")) {
            assertThat(OpenAiCertVisionGateway.output(JSON.readTree(body))).isNull();
        }
        var gateway = new OpenAiCertVisionGateway("https://api.openai.com/v1", "", "", 3, true);
        assertThat(gateway.isEnabled()).isFalse();
        assertThat(gateway.recognizeDocument(new byte[]{1}, "application/pdf", CertVisionPort.DocumentType.REGISTRATION)).isNull();
    }

    @Test void providerSelection() {
        var runner = new ApplicationContextRunner().withUserConfiguration(CertVisionGateway.class, OpenAiCertVisionGateway.class);
        runner.run(c -> assertThat(c.getBean(CertVisionPort.class)).isInstanceOf(CertVisionGateway.class));
        runner.withPropertyValues("shop.ai.vision.provider=openai", "shop.ai.vision.enabled=true", "shop.ai.vision.openai.model=model", "shop.ai.vision.openai.api-key=local")
                .run(c -> assertThat(c.getBean(CertVisionPort.class)).isInstanceOf(OpenAiCertVisionGateway.class));
        runner.withPropertyValues("shop.ai.vision.provider=openai", "shop.ai.vision.enabled=false", "shop.ai.vision.openai.enabled=true", "shop.ai.vision.openai.model=model", "shop.ai.vision.openai.api-key=local")
                .run(c -> assertThat(c.getBean(CertVisionPort.class).isEnabled()).isTrue());
    }

    private static String response(String text) throws Exception {
        return JSON.writeValueAsString(Map.of("status", "completed", "output", List.of(Map.of("type", "message", "content", List.of(Map.of("type", "output_text", "text", text))))));
    }
}
