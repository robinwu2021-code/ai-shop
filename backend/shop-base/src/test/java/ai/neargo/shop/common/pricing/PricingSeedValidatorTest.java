package ai.neargo.shop.common.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.ArrayNode;

class PricingSeedValidatorTest {
    private final PricingSeedValidator validator = new PricingSeedValidator();

    @Test
    void defaultSeedProducesTwentyRulesThreeDraftsAndTwoUnapprovedTaxCandidatesOnly() throws Exception {
        var plan = validator.validate(PricingConfigTestData.seed().toString(), "PRICING_RULE_SPEC_V4");
        assertThat(plan.ruleCount()).isEqualTo(20);
        assertThat(plan.templates()).hasSize(3);
        assertThat(plan.taxDraftCount()).isEqualTo(2);
        assertThat(plan.seedHash()).matches("[0-9a-f]{64}");
        assertThat(plan.provenanceSnapshot()).contains("regionalTaxProfiles", "REQUIRES_PLATFORM_REVIEW");
        assertThat(plan.provenanceSnapshot()).doesNotContain("storeDraftExamples", "ownerCapabilityFixtureExamples",
                "calculationVectors", "exampleOnly");
        assertThat(plan.templates()).extracting(PricingSeedValidator.TemplateDraft::industryCode)
                .containsExactly("FOOD", "RETAIL", "BEAUTY_SERVICE");
    }

    @Test
    void fullSourceHashIncludesIgnoredExamplesButDoesNotDependOnObjectKeyOrder() throws Exception {
        var seed = (ObjectNode) PricingConfigTestData.seed();
        var first = validator.validate(seed.toString(), "PRICING_RULE_SPEC_V4");
        var reverse = PricingConfigTestData.JSON.createObjectNode();
        var fields = new ArrayList<>(seed.propertyNames());
        Collections.reverse(fields);
        fields.forEach(field -> reverse.set(field, seed.get(field)));
        assertThat(validator.validate(reverse.toString(), "PRICING_RULE_SPEC_V4").seedHash())
                .isEqualTo(first.seedHash());
        seed.withArray("ownerCapabilityFixtureExamples").removeAll();
        assertThat(validator.validate(seed.toString(), "PRICING_RULE_SPEC_V4").seedHash())
                .isNotEqualTo(first.seedHash());
    }

    @Test
    void unknownEvaluatorMissingRuleAndMandatoryParameterChangesAreRejected() throws Exception {
        var seed = (ObjectNode) PricingConfigTestData.seed();
        var rule = (ObjectNode) seed.path("systemRules").get(0);
        rule.put("evaluatorType", "USER_SCRIPT");
        assertInvalid(seed);
        seed = (ObjectNode) PricingConfigTestData.seed();
        ((ArrayNode) seed.path("systemRules")).remove(0);
        assertInvalid(seed);
        seed = (ObjectNode) PricingConfigTestData.seed();
        ((ObjectNode) seed.path("systemRules").get(0).path("parameters")).put("basis", "STORE_SWITCH");
        assertInvalid(seed);
    }

    @Test
    void neitherAutoPublishNorStoreFixturesCanMasqueradeAsImportablePolicy() throws Exception {
        var seed = (ObjectNode) PricingConfigTestData.seed();
        ((ObjectNode) seed.path("importPolicy")).put("autoPublish", true);
        assertInvalid(seed);
        seed = (ObjectNode) PricingConfigTestData.seed();
        ((ObjectNode) seed.path("industryTemplates").get(0)).put("publicationStatus", "PUBLISHED");
        assertInvalid(seed);
        seed = (ObjectNode) PricingConfigTestData.seed();
        ((ObjectNode) seed.path("industryTemplates").get(0)).put("storeNo", "REAL_STORE");
        assertInvalid(seed);
    }

    @Test
    void duplicateTemplateIndustryOrTaxClassAndForgedTaxApprovalAreRejected() throws Exception {
        var seed = (ObjectNode) PricingConfigTestData.seed();
        ((ObjectNode) seed.path("industryTemplates").get(1)).put("industryCode", "FOOD");
        assertInvalid(seed);
        seed = (ObjectNode) PricingConfigTestData.seed();
        ((ObjectNode) seed.path("regionalTaxProfiles").get(0)).put("policyStatus", "APPROVED");
        assertInvalid(seed);
        seed = (ObjectNode) PricingConfigTestData.seed();
        ((ObjectNode) seed.path("regionalTaxProfiles").get(0).path("taxClasses").get(1)).put("code", "STANDARD");
        assertInvalid(seed);
    }

    @Test
    void malformedTaxRateLegalIntervalAndUnsupportedExclusiveContextAreRejected() throws Exception {
        var seed = (ObjectNode) PricingConfigTestData.seed();
        ((ObjectNode) seed.path("regionalTaxProfiles").get(0).path("taxClasses").get(0)).put("rateBp", 10001);
        assertInvalid(seed);
        seed = (ObjectNode) PricingConfigTestData.seed();
        ((ObjectNode) seed.path("regionalTaxProfiles").get(0)).put("effectiveTo", "2017-01-01");
        assertInvalid(seed);
        seed = (ObjectNode) PricingConfigTestData.seed();
        ((ObjectNode) seed.path("regionalTaxProfiles").get(1)).put("requiresContextEvidence", false);
        assertInvalid(seed);
    }

    @Test
    void unsupportedSchemaEngineAndRegistryVersionsFailClosed() throws Exception {
        var seed = (ObjectNode) PricingConfigTestData.seed();
        var originalSeed = seed.toString();
        assertThatThrownBy(() -> validator.validate(originalSeed, "DYNAMIC_RULES"))
                .isInstanceOf(IllegalArgumentException.class);
        seed.put("schemaVersion", 999);
        assertInvalid(seed);
        seed = (ObjectNode) PricingConfigTestData.seed();
        ((ObjectNode) seed.path("industryTemplates").get(0).path("definition")).put("formulaId", "RUN_SCRIPT");
        assertInvalid(seed);
    }

    @Test
    void duplicateFieldsUnknownFieldsTrailingJsonAndOversizeCannotBeNormalizedAway() throws Exception {
        var seed = (ObjectNode) PricingConfigTestData.seed();
        seed.put("executeSql", "DELETE");
        assertInvalid(seed);
        var text = PricingConfigTestData.seed().toString();
        assertThatThrownBy(() -> validator.validate(text + "{}", "PRICING_RULE_SPEC_V4"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validator.validate("{\"schemaVersion\":2,\"schemaVersion\":3}",
                "PRICING_RULE_SPEC_V4"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validator.validate(" ".repeat(65537), "PRICING_RULE_SPEC_V4"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void malformedRootAndTaxDatesUseTheValidationErrorContract() throws Exception {
        var seed = (ObjectNode) PricingConfigTestData.seed();
        seed.put("date", "not-a-date");
        assertInvalid(seed);
        for (String field : List.of("effectiveFrom", "effectiveTo")) {
            seed = (ObjectNode) PricingConfigTestData.seed();
            ((ObjectNode) seed.path("regionalTaxProfiles").get(0)).put(field, "2026-02-30");
            assertInvalid(seed);
        }
    }

    private void assertInvalid(ObjectNode seed) {
        assertThatThrownBy(() -> validator.validate(seed.toString(), "PRICING_RULE_SPEC_V4"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
