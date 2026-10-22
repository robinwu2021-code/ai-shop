package ai.neargo.shop.common.pricing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

final class PricingConfigTestData {
    static final JsonMapper JSON = JsonMapper.builder().build();

    private PricingConfigTestData() {
    }

    static JsonNode seed() throws IOException {
        return JSON.readTree(Files.readString(Path.of("../../docs/technical/design/schema/"
                + "pricing-initial-data.example.json")));
    }

    static String template() throws IOException {
        return seed().path("industryTemplates").get(0).path("definition").toString();
    }

    static PricingCapabilities capabilities() {
        return new PricingCapabilities(8, 4, true, true, true);
    }
}
