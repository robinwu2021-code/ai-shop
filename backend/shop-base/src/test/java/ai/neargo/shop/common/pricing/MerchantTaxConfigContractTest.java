package ai.neargo.shop.common.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

class MerchantTaxConfigContractTest {
    private final PricingConfigCompiler compiler = new PricingConfigCompiler();
    private final PricingSeedValidator validator = new PricingSeedValidator();

    @Test
    void legacyCompilationHashAndTaxRulesRemainUnchanged() throws Exception {
        var compiled = compile(PricingConfigTestData.template(), "{}");
        assertThat(compiled.valid()).isTrue();
        assertThat(compiled.config().configHash())
                .isEqualTo("0aa682619b4e84dc1ebb87191ea6308c2d67938bc7874f97f55f14521a5ddb5b");
        assertThat(compiled.config().rules()).allMatch(rule -> rule.ruleVersion() == 1);
    }

    @Test
    void merchantManagedDefinitionUsesNewTaxRulesWithoutChangingBenefitRules() throws Exception {
        var compiled = compile(merchantTemplate().toString(), "{}");
        assertThat(compiled.valid()).isTrue();
        assertThat(compiled.config().definition().schemaVersion()).isEqualTo(3);
        assertThat(compiled.config().definition().taxBinding().resolveFrom())
                .isEqualTo("MERCHANT_OR_PLATFORM_DEFAULT");
        assertThat(compiled.config().rules()).filteredOn(rule -> List.of("T01", "T02").contains(rule.ruleNo()))
                .allMatch(rule -> rule.ruleVersion() == 2).hasSize(2);
        assertThat(compiled.config().rules()).filteredOn(rule -> !List.of("T01", "T02").contains(rule.ruleNo()))
                .allMatch(rule -> rule.ruleVersion() == 1).hasSize(18);
        assertThat(compiled.config().configHash()).isEqualTo(compile(merchantTemplate().toString(), "{}")
                .config().configHash());
    }

    @Test
    void newDefinitionCannotReintroduceProfileReviewOrStoreLocalRateOverrides() throws Exception {
        for (String field : List.of("taxProfileNo", "resolveFrom", "storeMayEditRate", "missingPolicy")) {
            var template = merchantTemplate();
            var tax = (ObjectNode) template.path("taxBinding");
            switch (field) {
                case "taxProfileNo" -> tax.put(field, "REVIEWED_PROFILE");
                case "resolveFrom" -> tax.put(field, "VERIFIED_STORE_REGION_AND_TRANSACTION_CONTEXT");
                case "storeMayEditRate" -> tax.put(field, false);
                case "missingPolicy" -> tax.put(field, "ASSUME_ZERO");
                default -> throw new AssertionError(field);
            }
            assertThat(compile(template.toString(), "{}").valid()).as(field).isFalse();
        }
        for (String patch : List.of("{\"taxBinding\":{\"taxProfileNo\":\"LOCAL_PROFILE\"}}",
                "{\"taxBinding\":{\"rateBp\":500}}", "{\"schemaVersion\":2}")) {
            assertThat(compile(merchantTemplate().toString(), patch).valid()).as(patch).isFalse();
        }
    }

    @Test
    void newSeedIsDraftOnlyAndKeepsTaxDefaultAsAnExample() throws Exception {
        var seed = merchantSeed();
        assertThatNoException().isThrownBy(() -> validator.validate(seed.toString(), "PRICING_RULE_SPEC_V5"));
        var plan = validator.validate(seed.toString(), "PRICING_RULE_SPEC_V5");
        assertThat(plan.ruleCount()).isEqualTo(20);
        assertThat(plan.templates()).hasSize(3);
        assertThat(plan.taxDraftCount()).isZero();
        assertThat(plan.provenanceSnapshot()).contains("platformTaxDefaultExample");
        for (var template : plan.templates()) {
            assertThat(compile(template.definition(), "{}").config().definition().schemaVersion()).isEqualTo(3);
        }
        assertThatThrownBy(() -> validator.validate(seed.toString(), "PRICING_RULE_SPEC_V4"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validator.validate(PricingConfigTestData.seed().toString(), "PRICING_RULE_SPEC_V5"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void newSeedAcceptsBothModesAndExplicitZeroWithoutCoercion() throws Exception {
        for (int rate : new int[] {0, 500, 10000}) {
            for (String mode : List.of("INCLUSIVE", "EXCLUSIVE")) {
                var seed = merchantSeed();
                ((ObjectNode) seed.path("platformTaxDefaultExample")).put("rateBp", rate).put("priceTaxMode", mode);
                assertThatNoException().isThrownBy(() -> validator.validate(seed.toString(), "PRICING_RULE_SPEC_V5"));
                assertThat(validator.validate(seed.toString(), "PRICING_RULE_SPEC_V5").taxDraftCount()).isZero();
            }
        }
        for (String input : List.of("-1", "10001", "500.5", "\"500\"", "null")) {
            var seed = merchantSeed();
            ((ObjectNode) seed.path("platformTaxDefaultExample"))
                    .set("rateBp", PricingConfigTestData.JSON.readTree(input));
            assertThatThrownBy(() -> validator.validate(seed.toString(), "PRICING_RULE_SPEC_V5"))
                    .as(input).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void newSeedRejectsApprovalFlagsLegacyProfilesAndMixedRuleCatalogs() throws Exception {
        for (String mutation : List.of("approval", "profiles", "rules", "template", "unknown")) {
            var seed = merchantSeed();
            switch (mutation) {
                case "approval" -> ((ObjectNode) seed.path("importPolicy"))
                        .put("requireValidatedLegalTaxProfiles", true);
                case "profiles" -> seed.set("regionalTaxProfiles", PricingConfigTestData.seed()
                        .path("regionalTaxProfiles"));
                case "rules" -> seed.set("systemRules", PricingConfigTestData.seed().path("systemRules"));
                case "template" -> ((ObjectNode) seed.path("industryTemplates").get(0))
                        .set("definition", PricingConfigTestData.seed().path("industryTemplates").get(0)
                                .path("definition"));
                case "unknown" -> ((ObjectNode) seed.path("platformTaxDefaultExample")).put("approved", true);
                default -> throw new AssertionError(mutation);
            }
            assertThatThrownBy(() -> validator.validate(seed.toString(), "PRICING_RULE_SPEC_V5"))
                    .as(mutation).isInstanceOf(IllegalArgumentException.class);
        }
    }

    private ObjectNode merchantTemplate() throws Exception {
        var template = (ObjectNode) PricingConfigTestData.JSON.readTree(PricingConfigTestData.template());
        template.put("schemaVersion", 3);
        template.putObject("taxBinding").putNull("taxProfileNo")
                .put("resolveFrom", "MERCHANT_OR_PLATFORM_DEFAULT").put("missingPolicy", "BLOCK")
                .put("storeMayEditRate", true);
        return template;
    }

    private ObjectNode merchantSeed() throws Exception {
        return (ObjectNode) PricingConfigTestData.JSON.readTree(Files.readString(Path.of(
                "../../docs/technical/design/schema/pricing-merchant-tax-initial-data.example.json")));
    }

    private CompilationResult compile(String definition, String patch) {
        return compiler.compile(definition, patch, SystemConstraints.standard(), PricingConfigTestData.capabilities());
    }
}
