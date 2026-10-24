package ai.neargo.shop.product.dto;

import ai.neargo.shop.common.pricing.TaxClass;
import ai.neargo.shop.spi.product.GoodsPricingPolicyFacts;
import ai.neargo.shop.spi.product.GoodsPricingPolicyPort.Policy;
import ai.neargo.shop.spi.product.GoodsPricingPolicyPort.Scope;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import com.fasterxml.jackson.annotation.JsonCreator;
import tools.jackson.databind.JsonNode;
import java.util.Set;

/** Context comes from authenticated server identity, never from request JSON. */
public final class GoodsPricingPolicyCommands {
    private GoodsPricingPolicyCommands() { }
    public record Context(Scope scope, String actorNo) {
        public Context {
            if (scope == null) throw new IllegalArgumentException();
            GoodsPricingPolicyFacts.key(actorNo);
        }
    }
    public record SaveReq(@Min(0) @Max(9007199254740990L) long expectedVersion,
            @NotNull Boolean promotionEligible, @NotNull TaxClass taxClass) {
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static SaveReq decode(JsonNode node) {
            if (node == null || !node.isObject() || !node.propertyNames().equals(
                    Set.of("expectedVersion", "promotionEligible", "taxClass"))) throw invalid();
            var version = node.path("expectedVersion");
            if (!version.isIntegralNumber() || !version.canConvertToLong() || version.asLong() < 0
                    || version.asLong() >= 9007199254740991L || !node.path("promotionEligible").isBoolean()
                    || !node.path("taxClass").isString()) throw invalid();
            var tax = TaxClass.valueOf(node.path("taxClass").asString());
            if (tax == TaxClass.UNKNOWN) throw invalid();
            return new SaveReq(version.asLong(), node.path("promotionEligible").asBoolean(), tax);
        }
        private static IllegalArgumentException invalid() {
            return new IllegalArgumentException("Explicit closed SKU pricing policy required");
        }
    }
    public record PolicyVO(String policyNo, String goodsNo, String skuNo, long version,
            boolean promotionEligible, TaxClass taxClass) {
        public static PolicyVO from(Policy value) {
            return new PolicyVO(value.policyNo(), value.goodsNo(), value.skuNo(), value.version(),
                    value.promotionEligible(), value.taxClass());
        }
    }
}
