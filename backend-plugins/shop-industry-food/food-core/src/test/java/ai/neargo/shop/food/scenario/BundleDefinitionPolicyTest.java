package ai.neargo.shop.food.scenario;

import static ai.neargo.shop.food.scenario.BundleServiceTest.choice;
import static ai.neargo.shop.food.scenario.BundleServiceTest.group;
import static ai.neargo.shop.food.scenario.BundleServiceTest.n;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.food.dto.BundleChoiceInput;
import ai.neargo.shop.food.dto.BundleGroupInput;
import ai.neargo.shop.food.dto.ReplaceBundleDefinitionReq;
import ai.neargo.shop.food.policy.BundleDefinitionPolicy;
import java.util.List;
import org.junit.jupiter.api.Test;

class BundleDefinitionPolicyTest {
    @Test void independentBusinessKeysCannotCollideThroughDelimiterConcatenation() {
        var req = new ReplaceBundleDefinitionReq(0L,
                List.of(group("a:b", "0", "1", null), group("a", "0", "1", null)),
                List.of(choice("x", "a:b", "c", "INCLUDED", "0", "1", "0"),
                        choice("y", "a", "b:c", "INCLUDED", "0", "1", "0")));
        assertThat(BundleDefinitionPolicy.normalize(req).choices()).hasSize(2);
    }

    @Test void impossibleCapacityDefaultsAndMissingDifferenceBaseAreRejected() {
        for (var req : List.of(
                new ReplaceBundleDefinitionReq(0L, List.of(group("g", "2", "2", null)),
                        List.of(choice("c", "g", "S", "INCLUDED", "0", "1", "0"))),
                new ReplaceBundleDefinitionReq(0L, List.of(group("g", "0", "1", null)),
                        List.of(choice("c", "g", "S", "INCLUDED", "1", "1", "0"),
                                choice("d", "g", "T", "INCLUDED", "0", "1", "1"))),
                new ReplaceBundleDefinitionReq(0L, List.of(group("g", "0", "1", null)),
                        List.of(choice("c", "g", "S", "DIFF_BASE", "0", "1", "0"))),
                new ReplaceBundleDefinitionReq(0L, List.of(group("g", "0", "1", null)), List.of()),
                new ReplaceBundleDefinitionReq(0L, List.of(),
                        List.of(choice("c", "missing", "S", "INCLUDED", "0", "1", "0"))),
                new ReplaceBundleDefinitionReq(0L, List.of(group("g", "0", "2", null)),
                        List.of(choice("c", "g", "S", "INCLUDED", "0", "1", "0"),
                                choice("d", "g", "S", "INCLUDED", "0", "1", "0"))))) {
            assertThatThrownBy(() -> BundleDefinitionPolicy.normalize(req)).isInstanceOf(BizException.class);
        }
    }

    @Test void exactDecimalBoundsArePreservedWithoutRoundingOrHugeExponentExpansion() {
        var max = group("g", "0", "999999999999.999999", null);
        var c = new BundleChoiceInput("c", "g", "S", n("999999999.999"), "INCLUDED",
                n("0"), n("0"), n("999999999999.999999"), 0);
        var normalized = BundleDefinitionPolicy.normalize(new ReplaceBundleDefinitionReq(0L, List.of(max), List.of(c)));
        assertThat(normalized.choices().getFirst().unitsPerPick()).isEqualByComparingTo("999999999.999");
        for (String v : List.of("-1", "0.0000001", "1000000000000", "1E+2147483647")) {
            var g = new BundleGroupInput("g", "x", n("0"), n(v), null, 0);
            assertThatThrownBy(() -> BundleDefinitionPolicy.normalize(
                    new ReplaceBundleDefinitionReq(0L, List.of(g), List.of(c)))).isInstanceOf(BizException.class);
        }
        var badUnits = new BundleChoiceInput("c", "g", "S", n("0.0001"), "INCLUDED", n("0"), n("0"), n("1"), 0);
        assertThatThrownBy(() -> BundleDefinitionPolicy.normalize(new ReplaceBundleDefinitionReq(0L,
                List.of(group("g", "0", "1", null)), List.of(badUnits)))).isInstanceOf(BizException.class);
    }

    @Test void orderingAndNumericallyEquivalentInputsProduceTheSameCanonicalDefinition() {
        var req = new ReplaceBundleDefinitionReq(0L, List.of(group("b", "0.0", "1.00", null),
                group("a", "0", "1", null)), List.of(choice("y", "b", "S", "ADD_SKU", "0", "1", "0"),
                choice("x", "a", "T", "INCLUDED", "0", "1", "0")));
        var one = BundleDefinitionPolicy.normalize(req);
        var two = BundleDefinitionPolicy.normalize(new ReplaceBundleDefinitionReq(0L,
                one.groups().reversed(), one.choices().reversed()));
        assertThat(one).isEqualTo(two);
    }
}
