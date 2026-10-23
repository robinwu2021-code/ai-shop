package ai.neargo.shop.trade.policy;

import ai.neargo.shop.common.pricing.EffectivePricingConfig;
import ai.neargo.shop.common.pricing.PricingCapabilities;
import ai.neargo.shop.common.pricing.PricingConfigCompiler;
import ai.neargo.shop.common.pricing.PricingRuleCatalog;
import ai.neargo.shop.common.pricing.SystemConstraints;
import ai.neargo.shop.trade.entity.OrdPricingTemplateRevision;
import ai.neargo.shop.trade.entity.OrdStorePricingRevision;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.TreeMap;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Rebuilds the same schema3 publication from frozen inputs and current actual capabilities. */
public final class PricingRuntimeConfigPolicy {
    private static final JsonMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();
    private PricingRuntimeConfigPolicy() { }
    public static EffectivePricingConfig verify(OrdStorePricingRevision row, OrdPricingTemplateRevision template,
            PricingCapabilities capabilities) {
        require(row != null && template != null && capabilities != null);
        require(Integer.valueOf(3).equals(row.getSchemaVersion())
                && Integer.valueOf(3).equals(template.getSchemaVersion())
                && row.getTemplateRevisionNo().equals(template.getRevisionNo())
                && row.getIndustryCode().equals(template.getIndustryCode()));
        bounded(template.getDefinitionSnapshot());
        bounded(row.getOverrideSnapshot());
        bounded(row.getEffectiveSnapshot());
        var templateNode = JSON.readTree(template.getDefinitionSnapshot());
        JSON.readTree(row.getOverrideSnapshot());
        var effectiveNode = JSON.readTree(row.getEffectiveSnapshot());
        require(sha256(canonical(templateNode)).equals(template.getDefinitionHash()));
        var compilation = new PricingConfigCompiler().compile(
                template.getDefinitionSnapshot(), row.getOverrideSnapshot(),
                SystemConstraints.standard(), capabilities);
        require(compilation.valid());
        var config = compilation.config();
        require(config.definition().schemaVersion() == 3 && config.canonicalJson().equals(canonical(effectiveNode))
                && config.configHash().equals(row.getEffectiveHash()));
        String registry = PricingRuleCatalog.registryVersion(3);
        String catalog = sha256(canonical(JSON.valueToTree(config.rules())));
        require(registry.equals(row.getEngineSpecVersion()) && registry.equals(template.getEngineSpecVersion())
                && catalog.equals(row.getRuleCatalogVersion()) && catalog.equals(template.getRuleCatalogVersion()));
        return config;
    }
    public static String catalogHash(EffectivePricingConfig config) {
        return sha256(canonical(JSON.valueToTree(config.rules())));
    }
    public static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static String canonical(JsonNode node) {
        if (node.isObject()) {
            var fields = new TreeMap<String, JsonNode>();
            node.properties().forEach(field -> fields.put(field.getKey(), field.getValue()));
            var result = JSON.createObjectNode();
            fields.forEach((name, value) -> result.set(name, JSON.readTree(canonical(value))));
            return result.toString();
        }
        if (node.isArray()) {
            var result = JSON.createArrayNode(); node.forEach(item -> result.add(JSON.readTree(canonical(item))));
            return result.toString();
        }
        return node.toString();
    }
    private static void bounded(String value) {
        require(value != null && !value.isBlank() && value.getBytes(StandardCharsets.UTF_8).length <= 1048576);
    }
    private static void require(boolean valid) {
        if (!valid) throw new IllegalArgumentException("Invalid current pricing publication");
    }
}
