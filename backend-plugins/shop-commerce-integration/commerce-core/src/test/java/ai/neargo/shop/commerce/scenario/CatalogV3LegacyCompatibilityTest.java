package ai.neargo.shop.commerce.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import ai.neargo.shop.commerce.policy.CatalogDocumentPolicy;
import ai.neargo.shop.spi.commerce.ChannelCatalogSourcePort.Document;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class CatalogV3LegacyCompatibilityTest {
    record Golden(String document, String hash) { }
    @Test void priorCompiledArtifactVersionOneAndTwoBytesAndHashesAreUnchanged() throws Exception {
        var json = JsonMapper.builder().findAndAddModules().build();
        try (var stream = getClass().getResourceAsStream("/catalog-legacy-v1-v2-golden.json")) {
            assertThat(stream).isNotNull();
            Map<String, Golden> goldens = json.readValue(new String(stream.readAllBytes(), StandardCharsets.UTF_8),
                    json.getTypeFactory().constructMapType(Map.class, String.class, Golden.class));
            assertThat(goldens).containsOnlyKeys("v1", "v2");
            for (var entry : goldens.entrySet()) {
                var expected = entry.getValue(); var document = json.readValue(expected.document(), Document.class);
                var frozen = CatalogDocumentPolicy.freeze(document, document.sources(), json);
                assertThat(json.writeValueAsString(frozen)).as(entry.getKey()).isEqualTo(expected.document());
                assertThat(CatalogDocumentPolicy.hash(frozen, json)).as(entry.getKey()).isEqualTo(expected.hash());
            }
        }
    }
}
