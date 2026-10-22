package ai.neargo.shop.common.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

class PricingConfigCompilerTest {
    private final PricingConfigCompiler compiler = new PricingConfigCompiler();

    @Test
    void compilesAllThreeInitialIndustryTemplatesToImmutableTypedConfigs() throws Exception {
        for (var template : PricingConfigTestData.seed().path("industryTemplates")) {
            var result = compile(template.path("definition").toString(), "{}");
            assertThat(result.valid()).isTrue();
            assertThat(result.config().definition().couponLimits().total()).isEqualTo(2);
            assertThat(result.config().rules()).hasSize(20);
            assertThat(result.config().configHash()).hasSize(64);
        }
    }

    @Test
    void rejectsUnknownFieldsProtectedPathsAndNullOverrides() throws Exception {
        for (String override : new String[] {"{\"pointEnabled\":false}", "{\"pointsEnabled\":null}",
                "{\"ruleRefs\":[]}", "{\"formulaId\":\"LEGACY_OWNER_COMPAT\"}",
                "{\"taxBinding\":{\"storeMayEditRate\":true}}",
                "{\"manualOperations\":{\"limits\":{\"missingPolicy\":\"ALLOW\"}}}"}) {
            assertThat(compile(PricingConfigTestData.template(), override).valid()).as(override).isFalse();
        }
    }

    @Test
    void preservesFalseReplacesArraysAndResetsOverridesToInheritance() throws Exception {
        String patch = "{\"pointsEnabled\":false,\"allowedCouponCombinations\":[]}";
        var changed = compile(PricingConfigTestData.template(), patch);
        assertThat(changed.valid()).isTrue();
        assertThat(changed.config().definition().pointsEnabled()).isFalse();
        assertThat(changed.config().definition().allowedCouponCombinations()).isEmpty();
        var reset = compiler.compile(PricingConfigTestData.template(), patch, Set.of("pointsEnabled"),
                SystemConstraints.standard(), PricingConfigTestData.capabilities());
        assertThat(reset.valid()).isTrue();
        assertThat(reset.config().definition().pointsEnabled()).isTrue();
        assertThat(reset.config().definition().allowedCouponCombinations()).isEmpty();
    }

    @Test
    void rejectsSingleCouponWithMultipleTotalAndInconsistentPercentLimits() throws Exception {
        assertThat(compile(PricingConfigTestData.template(), "{\"couponMode\":\"SINGLE\"}").valid()).isFalse();
        assertThat(compile(PricingConfigTestData.template(),
                "{\"couponLimits\":{\"percent\":0}}").valid()).isFalse();
        assertThat(compile(PricingConfigTestData.template(),
                "{\"couponLimits\":{\"cash\":-1}}").valid()).isFalse();
    }

    @Test
    void validatesManualRateBoundsWithoutGrantingApprovalAuthority() throws Exception {
        for (int rate : new int[] {-1, 10001}) {
            assertThat(compile(PricingConfigTestData.template(),
                    "{\"manualOperations\":{\"minPayableRateBp\":" + rate + "}}").valid()).isFalse();
        }
        assertThat(compile(PricingConfigTestData.template(),
                "{\"manualOperations\":{\"minPayableRateBp\":8000}}").valid()).isTrue();
    }

    @Test
    void rejectsCoercedBooleansFractionsAndUnknownEnumValues() throws Exception {
        for (String patch : new String[] {"{\"pointsEnabled\":\"false\"}",
                "{\"couponLimits\":{\"total\":2.5}}", "{\"couponLimits\":{\"total\":\"2\"}}",
                "{\"couponMode\":\"anything\"}"}) {
            assertThat(compile(PricingConfigTestData.template(), patch).valid()).as(patch).isFalse();
        }
    }

    @Test
    void rejectsDuplicateKeysTrailingTokensAndExcessiveInput() throws Exception {
        for (String patch : new String[] {"{\"pointsEnabled\":true,\"pointsEnabled\":false}", "{} {}",
                " ".repeat(65537), "{\"pointsEnabled\":" + "[".repeat(40) + "0" + "]".repeat(40) + "}"}) {
            assertThat(compile(PricingConfigTestData.template(), patch).valid()).isFalse();
        }
    }

    @Test
    void checksMandatoryReferencesAndTemplateVersionEvenBeforePublication() throws Exception {
        ObjectNode template = (ObjectNode) PricingConfigTestData.JSON.readTree(PricingConfigTestData.template());
        template.putArray("ruleRefs").add("B01");
        assertThat(compile(template.toString(), "{}").valid()).isFalse();
        template = (ObjectNode) PricingConfigTestData.JSON.readTree(PricingConfigTestData.template());
        template.put("schemaVersion", 99);
        assertThat(compile(template.toString(), "{}").valid()).isFalse();
    }

    @Test
    void requiresExactOwnerCapabilitiesForActivatedMultiCouponStackAndStoredPolicies() throws Exception {
        var limited = new PricingCapabilities(1, 1, false, false, false);
        var result = compiler.compile(PricingConfigTestData.template(), "{}", SystemConstraints.standard(), limited);
        assertThat(result.valid()).isFalse();
        assertThat(result.config()).isNull();
        assertThat(result.issues()).isNotEmpty();
        var noStored = new PricingCapabilities(2, 1, true, false, false);
        result = compiler.compile(PricingConfigTestData.template(), "{\"storedValueEnabled\":true}",
                SystemConstraints.standard(), noStored);
        assertThat(result.valid()).isFalse();
    }

    @Test
    void compilesLegitimateMultiplePercentDisjointOverride() throws Exception {
        String patch = """
                {"couponLimits":{"total":3,"percent":2},
                 "discountCouponPolicy":{"maxUsedPerOrder":2}}
                """;
        var result = compile(PricingConfigTestData.template(), patch);
        assertThat(result.valid()).isTrue();
        assertThat(result.config().definition().discountCouponPolicy().maxUsedPerOrder()).isEqualTo(2);
    }

    @Test
    void hashesByCanonicalContentInsteadOfJsonObjectOrderOrMutableNodes() throws Exception {
        var a = compile(PricingConfigTestData.template(), "{\"pointsEnabled\":false,\"storedValueEnabled\":false}");
        var b = compile(PricingConfigTestData.template(), "{\"storedValueEnabled\":false,\"pointsEnabled\":false}");
        assertThat(a.valid()).isTrue();
        assertThat(a.config()).isEqualTo(b.config());
        assertThat(a.config().configHash()).isNotEqualTo(
                compile(PricingConfigTestData.template(), "{}").config().configHash());
    }

    @Test
    void rejectsUnknownResetPathsAndDoesNotTreatEmptyObjectsAsDeletingDefaults() throws Exception {
        assertThat(compiler.compile(PricingConfigTestData.template(), "{}", Set.of("ruleRefs"),
                SystemConstraints.standard(), PricingConfigTestData.capabilities()).valid()).isFalse();
        var result = compile(PricingConfigTestData.template(), "{\"couponLimits\":{}}");
        assertThat(result.valid()).isTrue();
        assertThat(result.config().definition().couponLimits().total()).isEqualTo(2);
    }

    @Test
    void keepsCompiledListsDeeplyImmutable() throws Exception {
        var config = compile(PricingConfigTestData.template(), "{}").config();
        assertThatThrownBy(() -> config.rules().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> config.definition().allowedCouponCombinations().get(0).clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> config.definition().manualOperations().reasonCategories().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void stackingNeedsCoherentCountsAndExplicitProviderSupport() throws Exception {
        String patch = """
                {"couponLimits":{"total":3,"percent":2},
                 "discountCouponPolicy":{"maxUsedPerOrder":2,"overlapMode":"STACK",
                                         "maxUsedPerItem":2,"stackingEnabled":true}}
                """;
        var supported = compile(PricingConfigTestData.template(), patch);
        assertThat(supported.valid()).isTrue();
        var unsupported = compiler.compile(PricingConfigTestData.template(), patch, SystemConstraints.standard(),
                new PricingCapabilities(3, 2, true, false, false));
        assertThat(unsupported.valid()).isFalse();
        assertThat(unsupported.issues().get(0).code()).isEqualTo("OWNER_CAPABILITY_UNSUPPORTED");
    }

    @Test
    void rejectsWeakenedTemplatePoliciesAndNullEvenWhenResetWasRequested() throws Exception {
        var template = (ObjectNode) PricingConfigTestData.JSON.readTree(PricingConfigTestData.template());
        template.put("activityAndCoupons", "ALLOW");
        assertThat(compile(template.toString(), "{}").valid()).isFalse();
        var reset = compiler.compile(PricingConfigTestData.template(), "{\"pointsEnabled\":null}",
                Set.of("pointsEnabled"), SystemConstraints.standard(), PricingConfigTestData.capabilities());
        assertThat(reset.valid()).isFalse();
    }

    @Test
    void rejectsNonCanonicalBusinessReferencesWithoutPretendingToResolveTheirExistence() throws Exception {
        ObjectNode patch = PricingConfigTestData.JSON.createObjectNode();
        patch.putObject("taxBinding").put("taxProfileNo", "T".repeat(33));
        assertThat(compile(PricingConfigTestData.template(), patch.toString()).valid()).isFalse();
        patch = PricingConfigTestData.JSON.createObjectNode();
        patch.putObject("manualOperations").putArray("reasonCategories").add("BAD\nCODE");
        assertThat(compile(PricingConfigTestData.template(), patch.toString()).valid()).isFalse();
        assertThat(compile(PricingConfigTestData.template(),
                "{\"taxBinding\":{\"taxProfileNo\":\"UNKNOWN_PROFILE\"}}").valid()).isTrue();
    }

    @Test
    void rejectsNumericStringEnumAliasesBeforeTypedDecoding() throws Exception {
        for (String patch : new String[] {"{\"couponMode\":\"1\"}",
                "{\"benefitCombinationMode\":\"0\"}", "{\"manualOperations\":{\"amountBasis\":\"0\"}}",
                "{\"discountCouponPolicy\":{\"overlapMode\":\"0\"}}"}) {
            assertThat(compile(PricingConfigTestData.template(), patch).valid()).as(patch).isFalse();
        }
        var template = (ObjectNode) PricingConfigTestData.JSON.readTree(PricingConfigTestData.template());
        template.putArray("industryRestrictions").add("0");
        assertThat(compile(template.toString(), "{}").valid()).isFalse();
    }

    private CompilationResult compile(String template, String override) {
        return compiler.compile(template, override, SystemConstraints.standard(), PricingConfigTestData.capabilities());
    }
}
