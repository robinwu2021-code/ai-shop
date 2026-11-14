package ai.neargo.shop.promotion.cost;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.promotion.cost.BrandPromotionCostModels.SaveRule;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

class BrandPromotionCostInputTest {
    @Test void ordinaryPublishedResourcesRequireCnyAndAConservedFundingRatio() {
        try(var factory=Validation.buildDefaultValidatorFactory()) {
            var validator=factory.getValidator();
            assertThat(validator.validate(new SaveRule("rule","CNY",3300,6700))).isEmpty();
            assertThat(validator.validate(new SaveRule("rule","USD",3300,6700))).isNotEmpty();
            assertThat(validator.validate(new SaveRule("rule","CNY",3300,6600))).isNotEmpty();
        }
    }
}
