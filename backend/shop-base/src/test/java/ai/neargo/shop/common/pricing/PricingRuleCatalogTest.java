package ai.neargo.shop.common.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class PricingRuleCatalogTest {
    @Test
    void decodesAllTwentySeedRulesIntoClosedTypedParameters() throws Exception {
        var rules = PricingRuleCatalog.validateJson(PricingConfigTestData.seed().path("systemRules").toString());
        assertThat(rules).hasSize(20);
        assertThat(rules.get(1).parameters()).isInstanceOf(RuleDefinition.ForbiddenCombination.class);
        assertThat(rules.get(3).parameters()).isInstanceOf(RuleDefinition.CountLimits.class);
        assertThat(rules.get(5).parameters()).isInstanceOf(RuleDefinition.FixedPolicyParameters.class);
        assertThatThrownBy(rules::clear).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsUnknownEvaluatorVersionParametersAndMandatoryWeakening() throws Exception {
        for (String field : new String[] {"evaluatorType", "ruleVersion", "mandatory", "parameters"}) {
            ArrayNode rules = (ArrayNode) PricingConfigTestData.seed().path("systemRules");
            ObjectNode b02 = (ObjectNode) rules.get(1);
            switch (field) {
                case "evaluatorType" -> b02.put(field, "RUN_SCRIPT");
                case "ruleVersion" -> b02.put(field, 99);
                case "mandatory" -> b02.put(field, false);
                default -> ((ObjectNode) b02.get(field)).putArray("rightKinds").add("POINTS");
            }
            assertThatThrownBy(() -> PricingRuleCatalog.validateJson(rules.toString()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void rejectsMissingDuplicateAndExtraRuleIdentities() throws Exception {
        ArrayNode missing = (ArrayNode) PricingConfigTestData.seed().path("systemRules");
        missing.remove(1);
        assertThatThrownBy(() -> PricingRuleCatalog.validateJson(missing.toString()))
                .isInstanceOf(IllegalArgumentException.class);
        ArrayNode duplicate = (ArrayNode) PricingConfigTestData.seed().path("systemRules");
        duplicate.set(1, duplicate.get(0));
        assertThatThrownBy(() -> PricingRuleCatalog.validateJson(duplicate.toString()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
