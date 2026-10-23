package ai.neargo.shop.trade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import ai.neargo.shop.trade.service.PricingSeedService.ImportMode;
import tools.jackson.databind.node.ObjectNode;
import ai.neargo.shop.trade.dto.PricingConfigRequests;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

class PricingConfigRequestsTest {
    private final JsonMapper json = JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .build();

    @Test
    void evenLenientGlobalJsonCannotAdmitClientIdentityCapabilitiesOrTaxEvidence() {
        var fixtures = List.of(new PricingConfigRequests.Create("FOOD", "DEFAULT", "Default", "{}"),
                new PricingConfigRequests.Save(0, "{}", null), new PricingConfigRequests.Preview(0, null),
                new PricingConfigRequests.Publish(0, null, "hash", "reason", null),
                new PricingConfigRequests.Initialize("PR1", "init"),
                new PricingConfigRequests.Reset(0, List.of("couponMode")),
                new PricingConfigRequests.Upgrade(0, "PR1"),
                new PricingConfigRequests.Seed("{}", ImportMode.DRY_RUN));
        for (Object fixture : fixtures) {
            ObjectNode baseline = json.valueToTree(fixture);
            assertThat(json.treeToValue(baseline, fixture.getClass())).isEqualTo(fixture);
            for (String field : List.of("context", "tenantNo", "actorNo", "entityNo", "storeNo", "capabilities",
                    "verifiedTaxProfiles", "registryVersion")) {
                var forged = baseline.deepCopy();
                forged.put(field, "FORGED");
                assertThatThrownBy(() -> json.treeToValue(forged, fixture.getClass()))
                        .hasStackTraceContaining("Unknown pricing request field");
            }
        }
    }

    @Test
    void omittedExpectedVersionIsNotSilentlyTreatedAsAnInitialDraft() {
        assertThatThrownBy(() -> json.readValue("{\"content\":\"{}\"}", PricingConfigRequests.Save.class))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> json.readValue("{\"expectedDraftVersion\":null,\"content\":\"{}\"}",
                PricingConfigRequests.Save.class)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void reasonLengthMatchesTheExistingPublicationLimit() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(validator.validate(new PricingConfigRequests.Publish(0, null, "hash", "R".repeat(256), null)))
                    .isEmpty();
            assertThat(validator.validate(new PricingConfigRequests.Publish(0, null, "hash", "R".repeat(257), null)))
                    .isNotEmpty();
            assertThat(validator.validate(new PricingConfigRequests.Initialize("PR1", "R".repeat(257))))
                    .isNotEmpty();
        }
    }

    @Test
    void negativeVersionsEmptyDefinitionsAndOversizeSeedsFailValidation() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(validator.validate(new PricingConfigRequests.Save(-1, "", null))).isNotEmpty();
            assertThat(validator.validate(new PricingConfigRequests.Seed(" ".repeat(65537), null))).isNotEmpty();
            assertThat(validator.validate(new PricingConfigRequests.Preview(0, null))).isEmpty();
            assertThat(validator.validate(new PricingConfigRequests.Publish(0, null, "hash", "", null))).isNotEmpty();
        }
    }
}
