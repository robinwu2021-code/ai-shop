package ai.neargo.shop.common.pricing;

import java.util.Set;
import tools.jackson.core.JacksonException;

/** Explicit merchant override or inheritance marker; this value never authenticates its owner. */
public record TaxSettingDefinition(boolean custom, Integer rateBp, PriceTaxMode priceTaxMode) {
    public TaxSettingDefinition {
        if (custom ? rateBp == null || rateBp < 0 || rateBp > 10000 || priceTaxMode == null
                : rateBp != null || priceTaxMode != null) {
            throw new IllegalArgumentException(
                    "Explicit bounded tax rate/mode or an empty inheritance marker required");
        }
    }

    public static TaxSettingDefinition parse(String source) {
        try {
            var node = PricingJson.parse(source);
            var fields = Set.of("schemaVersion", "custom", "rateBp", "priceTaxMode");
            PricingJson.fields(node, fields, fields);
            if (!node.path("schemaVersion").isIntegralNumber() || node.path("schemaVersion").asLong() != 1
                    || !node.path("custom").isBoolean()) {
                throw new IllegalArgumentException("Unsupported tax setting format");
            }
            var rate = node.path("rateBp");
            var mode = node.path("priceTaxMode");
            if (!rate.isNull() && (!rate.isIntegralNumber() || !rate.canConvertToInt())
                    || !mode.isNull() && !mode.isString()) {
                throw new IllegalArgumentException("Tax setting values cannot be coerced");
            }
            return new TaxSettingDefinition(node.path("custom").booleanValue(),
                    rate.isNull() ? null : rate.asInt(), mode.isNull() ? null : PriceTaxMode.valueOf(mode.asString()));
        } catch (JacksonException malformed) {
            throw new IllegalArgumentException("Invalid bounded tax setting JSON", malformed);
        }
    }

    public String canonicalJson() {
        var node = PricingJson.JSON.createObjectNode().put("schemaVersion", 1).put("custom", custom);
        if (rateBp == null) node.putNull("rateBp");
        else node.put("rateBp", rateBp);
        if (priceTaxMode == null) node.putNull("priceTaxMode");
        else node.put("priceTaxMode", priceTaxMode.name());
        return PricingJson.canonical(node);
    }

    public String snapshotHash(String owner, long version) {
        PricingPortion.requireIdentity(owner, 64);
        if (version < 0 || version > 9007199254740991L) {
            throw new IllegalArgumentException("JSON-safe tax setting version required");
        }
        return PricingJson.hash("TAX_SETTING_V1\n" + owner + "\n" + version + "\n" + canonicalJson());
    }
}
