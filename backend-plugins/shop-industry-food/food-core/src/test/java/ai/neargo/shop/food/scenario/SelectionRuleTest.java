package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.food.dto.OptionItemInput;
import ai.neargo.shop.food.dto.ReplaceOptionGroupItemsReq;
import ai.neargo.shop.food.dto.SelectionRules;
import ai.neargo.shop.food.dto.SelectionRules.SelectionDependency;
import ai.neargo.shop.food.policy.OptionSelectionPolicy;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class SelectionRuleTest {
    static OptionItemInput item(String id, String ref, String min, String max, String def) {
        return new OptionItemInput(id, "SPEC_VALUE", ref, null, new BigDecimal(min), new BigDecimal(max),
                new BigDecimal(def), 0);
    }
    static ReplaceOptionGroupItemsReq req(List<OptionItemInput> items, SelectionRules rules) {
        return new ReplaceOptionGroupItemsReq(0L, items, List.of(), rules);
    }
    static SelectionRules rules(List<List<String>> mutex, String item, String... needs) {
        return new SelectionRules(mutex, item == null ? List.of() : List.of(
                new SelectionDependency(item, List.of(needs))));
    }
    @Test void canonicalizesOrderAndQuantitiesWithoutChangingBusinessIdentities() {
        var result = OptionSelectionPolicy.normalize(req(List.of(item("B", "VB", "0.0", "1.00", "0"),
                item("A", "VA", "0", "1", "0")), rules(List.of(List.of("B", "A")), null)));
        assertThat(result.items()).extracting(OptionItemInput::optionItemNo).containsExactly("A", "B");
        assertThat(result.items().getFirst().maxQty()).isEqualTo(BigDecimal.ONE);
        assertThat(result.selectionRules().mutexSets()).containsExactly(List.of("A", "B"));
    }
    @Test void rejectsDuplicateIdentitySourceAndInvalidReferenceXor() {
        var a = item("A", "VA", "0", "1", "0");
        for (var items : List.of(List.of(a, a), List.of(a, item("B", "VA", "0", "1", "0")),
                List.of(new OptionItemInput("B", "MODIFIER", "VA", "M", BigDecimal.ZERO,
                        BigDecimal.ONE, BigDecimal.ZERO, 0)))) {
            bad(req(items, rules(List.of(), null)));
        }
    }
    @Test void rejectsNegativeInvertedOverPrecisionAndOverflowQuantities() {
        for (var a : List.of(item("A", "VA", "-1", "1", "0"), item("A", "VA", "1", "0", "0"),
                item("A", "VA", "1", "2", "0"), item("A", "VA", "0", "1", "2"),
                item("A", "VA", "0", "1", "0.0000001"), item("A", "VA", "0", "1E+2147483647", "0"))) {
            bad(req(List.of(a), rules(List.of(), null)));
        }
    }
    @Test void rejectsUnknownDuplicateAndSelfRuleReferences() {
        var a = item("A", "VA", "0", "1", "0");
        for (var rule : List.of(rules(List.of(List.of("A", "MISSING")), null),
                rules(List.of(List.of("A", "A")), null), rules(List.of(), "A", "A"),
                rules(List.of(), "A", "MISSING"))) bad(req(List.of(a), rule));
    }
    @Test void rejectsDependencyCyclesAndTransitiveMutexContradictions() {
        var items = List.of(item("A", "VA", "0", "1", "0"), item("B", "VB", "0", "1", "0"),
                item("C", "VC", "0", "1", "0"));
        var dependencies = List.of(new SelectionDependency("A", List.of("B")),
                new SelectionDependency("B", List.of("C")));
        bad(req(items, new SelectionRules(List.of(List.of("A", "C")), dependencies)));
        bad(req(items, new SelectionRules(List.of(), List.of(new SelectionDependency("A", List.of("B")),
                new SelectionDependency("B", List.of("A"))))));
    }
    @Test void validatesDefaultSelectionAndRequiredExclusiveOptions() {
        var items = List.of(item("A", "VA", "0", "1", "1"), item("B", "VB", "0", "1", "0"));
        bad(req(items, rules(List.of(), "A", "B")));
        var required = List.of(item("A", "VA", "1", "1", "1"), item("B", "VB", "1", "1", "1"));
        bad(req(required, rules(List.of(List.of("A", "B")), null)));
        assertThat(OptionSelectionPolicy.normalize(req(items, rules(List.of(), null))).items()).hasSize(2);
    }
    private void bad(ReplaceOptionGroupItemsReq req) {
        assertThatThrownBy(() -> OptionSelectionPolicy.normalize(req)).isInstanceOf(BizException.class);
    }
}
