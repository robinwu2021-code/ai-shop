package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.food.dto.BundleChoiceInput;
import ai.neargo.shop.food.dto.BundleGroupInput;
import ai.neargo.shop.food.dto.OptionItemInput;
import ai.neargo.shop.food.dto.ReplaceBundleDefinitionReq;
import ai.neargo.shop.food.dto.ReplaceOptionGroupItemsReq;
import ai.neargo.shop.food.dto.SelectionRules;
import ai.neargo.shop.food.dto.SelectionRules.SelectionDependency;
import ai.neargo.shop.food.policy.BundleDefinitionPolicy;
import ai.neargo.shop.food.policy.OptionPublicationPolicy;
import ai.neargo.shop.food.policy.SelectionFeasibility;
import ai.neargo.shop.food.policy.SelectionFeasibility.Status;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class FoodCatalogSelectionFeasibilityTest {
    static BigDecimal q(String number) { return new BigDecimal(number); }
    static OptionItemInput item(String id, String min, String max) {
        return new OptionItemInput(id, "SPEC_VALUE", id, null, q(min), q(max), q(min), 0);
    }
    static ReplaceOptionGroupItemsReq input(List<OptionItemInput> items, SelectionRules rules) {
        return new ReplaceOptionGroupItemsReq(0L, items, List.of(), rules);
    }
    static SelectionRules rules(List<List<String>> mutex, List<SelectionDependency> deps) {
        return new SelectionRules(mutex, deps);
    }
    static SelectionFeasibility.Result prove(String min, String max, ReplaceOptionGroupItemsReq input) {
        return OptionPublicationPolicy.prove(q(min), q(max), input, SelectionFeasibility.Budget.catalog());
    }
    static SelectionDependency dependency(String from, String to) {
        return new SelectionDependency(from, List.of(to));
    }

    @Test void defaultsCanWaitForCustomerSelectionAndEveryAlternativeRemainsReachable() {
        var config = input(List.of(item("A", "0", "1"), item("B", "0", "1")),
                rules(List.of(List.of("A", "B")), List.of()));
        assertThat(prove("1", "1", config).status()).isEqualTo(Status.PROVEN);
        assertThatCode(() -> OptionPublicationPolicy.validate(q("1"), q("1"), config))
                .doesNotThrowAnyException();
    }

    @Test void multiplePositiveMicroSelectionsNeedOneExactUnitEach() {
        var config = input(List.of(item("A", "0", "0.000001"), item("B", "0", "0.000001")),
                rules(List.of(), List.of(dependency("A", "B"))));
        var result = prove("0", "0.000001", config);
        assertThat(result.status()).isEqualTo(Status.UNREACHABLE);
        assertThat(result.unreachableItemNos()).containsExactly("A");
        assertThat(prove("0", "0.000002", config).status()).isEqualTo(Status.PROVEN);
    }

    @Test void mandatoryAndDependentChoicesCanMakeAnOtherwiseVisibleOptionDead() {
        var config = input(List.of(item("A", "1", "1"), item("B", "0", "1")),
                rules(List.of(List.of("A", "B")), List.of()));
        assertThatCode(() -> OptionPublicationPolicy.validate(q("1"), q("1"), config))
                .doesNotThrowAnyException();
        assertThat(prove("1", "1", config).unreachableItemNos()).containsExactly("B");
        var disabled = input(List.of(item("A", "0", "1"), item("B", "0", "0")),
                rules(List.of(), List.of(dependency("A", "B"))));
        assertThat(prove("0", "1", disabled).status()).isEqualTo(Status.UNREACHABLE);
        assertThat(prove("1", "1", disabled).status()).isEqualTo(Status.IMPOSSIBLE);
    }

    @Test void transitiveDependenciesAndOverlappingMutexAreEvaluatedTogether() {
        var config = input(List.of(item("A", "0", "1"), item("B", "0", "1"), item("C", "0", "1")),
                rules(List.of(List.of("A", "B"), List.of("A", "C"), List.of("B", "C")), List.of()));
        assertThat(prove("2", "3", config).status()).isEqualTo(Status.IMPOSSIBLE);
        var chain = input(config.items(), rules(List.of(), List.of(dependency("A", "B"), dependency("B", "C"))));
        assertThat(prove("0", "0.000002", chain).status()).isEqualTo(Status.UNREACHABLE);
        assertThat(prove("0", "0.000003", chain).status()).isEqualTo(Status.PROVEN);
    }

    @Test void ownerInputValidationRemainsStrictWithoutTurningMalformedRulesIntoNoSolution() {
        var items = List.of(item("A", "0", "1"), item("B", "0", "1"));
        for (var invalid : List.of(input(items, rules(List.of(), List.of(dependency("A", "MISSING")))),
                input(items, rules(List.of(), List.of(dependency("A", "B"), dependency("B", "A")))),
                input(List.of(item("A", "0", "0.0000001")), rules(List.of(), List.of()))))
            assertThatThrownBy(() -> prove("0", "1", invalid)).isInstanceOf(BizException.class);
    }

    @Test void catalogBudgetIsSharedAndExhaustionIsNotImpossible() {
        var config = input(List.of(item("A", "0", "1")), rules(List.of(), List.of()));
        var budget = new SelectionFeasibility.Budget(2);
        assertThat(OptionPublicationPolicy.prove(q("0"), q("1"), config, budget).status()).isEqualTo(Status.PROVEN);
        assertThat(budget.usedNodes()).isEqualTo(2);
        assertThat(OptionPublicationPolicy.prove(q("0"), q("1"), config, budget).status()).isEqualTo(Status.UNPROVEN);
        assertThat(budget.usedNodes()).isEqualTo(2);
        assertThatThrownBy(() -> new SelectionFeasibility.Budget(1000001)).isInstanceOf(BizException.class);
    }

    @Test void optionCapacitiesCannotExceedTheOriginalCheckoutCeiling() {
        var config = input(List.of(item("A", "0", "1000000")), rules(List.of(), List.of()));
        assertThat(prove("1000000", "1000000", config).status()).isEqualTo(Status.IMPOSSIBLE);
        assertThat(prove("999999", "999999", config).status()).isEqualTo(Status.PROVEN);
    }

    @Test void sortingAndEquivalentDecimalsDoNotChangeOutcomeOrBudget() {
        var items = List.of(item("B", "0.000000", "1.00"), item("A", "0", "1"));
        var first = prove("1", "1", input(items, rules(List.of(List.of("A", "B")), List.of())));
        var second = prove("1.0", "1.00", input(items.reversed(), rules(List.of(List.of("B", "A")), List.of())));
        assertThat(first).isEqualTo(second);
    }

    @Test void bundleFractionsRemainOwnerQuantitiesAndMultipliersAreNotGroupTotals() {
        var request = bundle("0.5", "0.5", "0", "0.5", "1.25");
        assertThat(BundleDefinitionPolicy.prove(request, SelectionFeasibility.Budget.catalog()).get("G").status())
                .isEqualTo(Status.PROVEN);
        var dead = bundle("0", "0", "0", "1", "0.5");
        assertThat(BundleDefinitionPolicy.prove(dead, SelectionFeasibility.Budget.catalog()).get("G").status())
                .isEqualTo(Status.UNREACHABLE);
        var over = bundle("1000000", "1000000", "0", "1000000", "1");
        assertThat(BundleDefinitionPolicy.prove(over, SelectionFeasibility.Budget.catalog()).get("G").status())
                .isEqualTo(Status.IMPOSSIBLE);
    }

    @Test void zeroCapacityOptionsDoNotNeedASelectionButCannotBeDependenciesOfReachableOptions() {
        var config = input(List.of(item("A", "0", "0")), rules(List.of(), List.of()));
        assertThat(prove("0", "0", config).status()).isEqualTo(Status.PROVEN);
        assertThat(prove("1", "1", config).status()).isEqualTo(Status.IMPOSSIBLE);
    }

    @Test void smallExactProblemsMatchIndependentEnumeration() {
        var random = new Random(27601);
        for (int sample = 0; sample < 180; sample++) {
            var items = new ArrayList<OptionItemInput>();
            for (int i = 0; i < 4; i++) items.add(item("I" + i, "0", "0.000002"));
            var mutex = new ArrayList<List<String>>();
            for (int i = 0; i < 4; i++) for (int j = i + 1; j < 4; j++)
                if (random.nextBoolean()) mutex.add(List.of("I" + i, "I" + j));
            var config = input(items, rules(mutex, List.of()));
            int min = random.nextInt(9), max = min + random.nextInt(9 - min);
            boolean any = false;
            boolean[] reachable = new boolean[4];
            for (int code = 0; code < 81; code++) {
                int bits = code, total = 0;
                int[] amounts = new int[4];
                for (int i = 0; i < 4; i++) { amounts[i] = bits % 3; bits /= 3; total += amounts[i]; }
                if (total < min || total > max) continue;
                boolean valid = mutex.stream().noneMatch(set -> amounts[Integer.parseInt(set.get(0).substring(1))] > 0
                        && amounts[Integer.parseInt(set.get(1).substring(1))] > 0);
                if (!valid) continue;
                any = true;
                for (int i = 0; i < 4; i++) reachable[i] |= amounts[i] > 0;
            }
            Status expected = !any ? Status.IMPOSSIBLE : Status.PROVEN;
            if (any) for (boolean found : reachable) if (!found) expected = Status.UNREACHABLE;
            var actual = OptionPublicationPolicy.prove(BigDecimal.valueOf(min, 6), BigDecimal.valueOf(max, 6),
                    config, SelectionFeasibility.Budget.catalog());
            assertThat(actual.status()).as("sample %s", sample).isEqualTo(expected);
        }
    }

    @Test void aSingleHardGroupCannotConsumeTheWholeCatalogBudget() {
        var items = new ArrayList<OptionItemInput>();
        var mutex = new ArrayList<List<String>>();
        for (int i = 0; i < 25; i++) {
            items.add(item("A" + i, "0", "1")); items.add(item("B" + i, "0", "1"));
            mutex.add(List.of("A" + i, "B" + i));
        }
        var budget = SelectionFeasibility.Budget.catalog();
        var result = OptionPublicationPolicy.prove(q("26"), q("26"), input(items, rules(mutex, List.of())), budget);
        assertThat(result.status()).isEqualTo(Status.UNPROVEN);
        assertThat(result.visitedNodes()).isEqualTo(100000);
        assertThat(budget.usedNodes()).isEqualTo(100000);
    }

    @Test void nonzeroDefaultsMustBeAcceptedByTheOriginalCartQuantityBoundary() {
        var config = input(List.of(new OptionItemInput("A", "SPEC_VALUE", "A", null,
                q("0"), q("1000000"), q("1000000"), 0)), rules(List.of(), List.of()));
        assertThatThrownBy(() -> prove("0", "1000000", config)).isInstanceOf(BizException.class);
        var bundle = new ReplaceBundleDefinitionReq(0L,
                List.of(new BundleGroupInput("G", "Group", q("0"), q("1000000"), null, 0)),
                List.of(new BundleChoiceInput("C", "G", "SKU", q("1"), "INCLUDED", q("1000000"),
                        q("0"), q("1000000"), 0)));
        assertThatThrownBy(() -> BundleDefinitionPolicy.prove(bundle, SelectionFeasibility.Budget.catalog()))
                .isInstanceOf(BizException.class);
    }

    static ReplaceBundleDefinitionReq bundle(String min, String max, String itemMin, String itemMax, String units) {
        return new ReplaceBundleDefinitionReq(0L,
                List.of(new BundleGroupInput("G", "Group", q(min), q(max), null, 0)),
                List.of(new BundleChoiceInput("C", "G", "SKU", q(units), "INCLUDED", q(itemMin),
                        q(itemMin), q(itemMax), 0)));
    }
}
