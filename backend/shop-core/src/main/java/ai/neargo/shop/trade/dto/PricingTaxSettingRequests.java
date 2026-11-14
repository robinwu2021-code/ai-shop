package ai.neargo.shop.trade.dto;

import java.util.Set;
import com.fasterxml.jackson.annotation.JsonCreator;
import tools.jackson.databind.JsonNode;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import ai.neargo.shop.common.pricing.PriceTaxMode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Closed management intent; tenant, merchant, source and actor are server-owned. */
public final class PricingTaxSettingRequests {
    private PricingTaxSettingRequests() { }

    public record Save(
            @JsonProperty(required = true) @JsonSetter(nulls = Nulls.FAIL)
            @Min(0) @Max(9007199254740990L) long expectedVersion,
            @JsonProperty(required = true) @JsonSetter(nulls = Nulls.FAIL)
            @Min(0) @Max(10000) int rateBp,
            @NotNull PriceTaxMode priceTaxMode) {
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static Save decode(JsonNode node) {
            fields(node, Set.of("expectedVersion", "rateBp", "priceTaxMode"));
            long version = integer(node, "expectedVersion");
            long rate = integer(node, "rateBp");
            if (rate < 0 || rate > 10000 || !node.path("priceTaxMode").isString()) throw invalid();
            return new Save(version, (int) rate, PriceTaxMode.valueOf(node.path("priceTaxMode").asString()));
        }
    }

    public record Reset(
            @JsonProperty(required = true) @JsonSetter(nulls = Nulls.FAIL)
            @Min(0) @Max(9007199254740990L) long expectedVersion) {
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static Reset decode(JsonNode node) {
            fields(node, Set.of("expectedVersion"));
            return new Reset(integer(node, "expectedVersion"));
        }
    }

    private static void fields(JsonNode node, Set<String> fields) {
        if (node == null || !node.isObject() || !node.propertyNames().equals(fields)) throw invalid();
    }

    private static long integer(JsonNode node, String field) {
        var value = node.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong()) throw invalid();
        long result = value.asLong();
        if (result < 0 || result > 9007199254740990L) throw invalid();
        return result;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Explicit closed integer tax setting intent required");
    }
}
