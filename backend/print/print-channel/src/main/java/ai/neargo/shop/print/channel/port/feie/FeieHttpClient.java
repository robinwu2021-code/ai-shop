package ai.neargo.shop.print.channel.port.feie;

import tools.jackson.databind.JsonNode;

import java.util.Map;

public interface FeieHttpClient {
    JsonNode call(
            String operation, Map<String, String> credentials, Map<String, String> parameters);
}
