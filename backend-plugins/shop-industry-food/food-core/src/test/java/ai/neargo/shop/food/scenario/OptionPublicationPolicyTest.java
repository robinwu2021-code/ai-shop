package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.food.dto.OptionItemInput;
import ai.neargo.shop.food.dto.ReplaceOptionGroupItemsReq;
import ai.neargo.shop.food.dto.SelectionRules;
import ai.neargo.shop.food.dto.SelectionRules.SelectionDependency;
import ai.neargo.shop.food.policy.OptionPublicationPolicy;
import ai.neargo.shop.food.policy.OptionSelectionPolicy;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class OptionPublicationPolicyTest {
    private OptionItemInput item(String no, String max, String def) {
        return new OptionItemInput(no, "SPEC_VALUE", no, null, BigDecimal.ZERO,
                new BigDecimal(max), new BigDecimal(def), 0);
    }
    private ReplaceOptionGroupItemsReq config(List<OptionItemInput> items, List<List<String>> mutex,
            List<SelectionDependency> requires) {
        return OptionSelectionPolicy.normalize(new ReplaceOptionGroupItemsReq(0L, items, List.of(),
                new SelectionRules(mutex, requires)));
    }
    @Test void requiredChoiceCanBePublishedWithoutAutomaticDefaults() {
        var c = config(List.of(item("A","1","0"),item("B","1","0")), List.of(List.of("A","B")), List.of());
        assertThatCode(() -> OptionPublicationPolicy.validate(BigDecimal.ONE, BigDecimal.ONE, c)).doesNotThrowAnyException();
        assertThatThrownBy(() -> OptionPublicationPolicy.validate(new BigDecimal("2"), new BigDecimal("2"), c))
                .isInstanceOf(BizException.class);
    }
    @Test void dependencyOnZeroCapacityCannotMakeARequiredGroupExecutable() {
        var c = config(List.of(item("A","1","0"),item("B","0","0")), List.of(),
                List.of(new SelectionDependency("A", List.of("B"))));
        assertThatThrownBy(() -> OptionPublicationPolicy.validate(BigDecimal.ONE, BigDecimal.ONE, c))
                .isInstanceOf(BizException.class);
    }
    @Test void defaultsCannotExceedTheGroupLimitEvenWhenAnotherSelectionExists() {
        var c = config(List.of(item("A","1","1"),item("B","1","1")), List.of(), List.of());
        assertThatThrownBy(() -> OptionPublicationPolicy.validate(BigDecimal.ZERO, BigDecimal.ONE, c))
                .isInstanceOf(BizException.class);
    }
    @Test void overlappingMutexRulesAreCheckedTogether() {
        var c = config(List.of(item("A","1","0"),item("B","1","0"),item("C","1","0")),
                List.of(List.of("A","B"),List.of("A","C"),List.of("B","C")), List.of());
        assertThatThrownBy(() -> OptionPublicationPolicy.validate(new BigDecimal("2"), new BigDecimal("3"), c))
                .isInstanceOf(BizException.class);
    }
}
