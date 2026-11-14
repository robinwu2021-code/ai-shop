package ai.neargo.shop.common.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.pricing.PricingBenefitBasis.Entry;
import ai.neargo.shop.common.pricing.PricingBenefitBasis.Snapshot;
import ai.neargo.shop.common.pricing.PricingRuleFacts.PricingRole;
import ai.neargo.shop.common.pricing.PricingRuleFacts.ScopeStatus;
import ai.neargo.shop.common.pricing.PricingRuleFacts.UnitPolicy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PricingBenefitBasisTest {
    private PricingUnit unit(String line, String component, String start, String end, long amount) {
        return new PricingUnit(line, component, "G", "S", "PRICE", line, new BigDecimal(start),
                new BigDecimal(end), new MoneyMinor("AED", 3), new MoneyMinor("AED", amount));
    }
    private UnitPolicy policy(PricingUnit unit, boolean eligible) {
        return new UnitPolicy(PricingPortion.from(unit), eligible, ScopeStatus.OPEN, null, PricingRole.NONE);
    }
    private PricingFacts facts(PricingUnit... units) {
        return new PricingFacts("AED", "a".repeat(64), 7, List.of(units));
    }

    @Test void partitionsEligibleAndExcludedPricesWithoutRemovingExcludedGoodsFromOriginalTotal() {
        var a = unit("L1", null, "0", "1", 1000); var b = unit("L2", null, "0", "1", 700);
        var basis = PricingBenefitBasis.freeze(facts(a, b), List.of(policy(b, false), policy(a, true)));
        assertThat(basis.priceTotalMinor()).isEqualTo(1700);
        assertThat(basis.eligibleMinor()).isEqualTo(1000);
        assertThat(basis.excludedMinor()).isEqualTo(700);
        assertThat(PricingBenefitBasis.eligiblePortions(basis)).hasSize(1);
        assertThat(basis.entries()).extracting(Entry::source).containsExactly(a, b);
        assertThat(PricingBenefitBasis.validate(basis)).isEqualTo(basis);
    }
    @Test void preservesComponentAndFractionalScopesAndNeverRepricesRoundedSourceAmounts() {
        var a = unit("L", null, "0", "0.5", 2);
        var b = unit("L", "TOPPING", "0", "0.5", 1);
        var basis = PricingBenefitBasis.freeze(facts(a, b), List.of(policy(a, true), policy(b, false)));
        assertThat(basis.eligibleMinor()).isEqualTo(2);
        assertThat(basis.excludedMinor()).isEqualTo(1);
        assertThat(basis.entries().getFirst().source().quantity()).isEqualByComparingTo("0.5");
        assertThat(PricingBenefitBasis.eligiblePortions(basis).getFirst().portion()).isEqualTo(PricingPortion.from(a));
        assertThat(basis.entries().get(1).source().componentRef()).isEqualTo("TOPPING");
    }
    @Test void completePolicySetIsRequiredWithNoUnknownOrPermissiveDefault() {
        var a = unit("L1", null, "0", "1", 100); var b = unit("L2", null, "0", "1", 200);
        assertThatThrownBy(() -> PricingBenefitBasis.freeze(facts(a, b), List.of(policy(a, true))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PricingBenefitBasis.freeze(facts(a, b), List.of(policy(a, true), policy(a, true))))
                .isInstanceOf(IllegalArgumentException.class);
        var unknown = new UnitPolicy(PricingPortion.from(b), true, ScopeStatus.UNKNOWN, null, PricingRole.NONE);
        assertThatThrownBy(() -> PricingBenefitBasis.freeze(facts(a, b), List.of(policy(a, true), unknown)))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void zeroAndFullyExcludedPricesDoNotInventAnEligiblePortion() {
        var a = unit("L1", null, "0", "1", 0); var b = unit("L2", null, "0", "1", 100);
        var basis = PricingBenefitBasis.freeze(facts(a, b), List.of(policy(a, true), policy(b, false)));
        assertThat(basis.eligibleMinor()).isZero();
        assertThat(basis.excludedMinor()).isEqualTo(100);
        assertThat(PricingBenefitBasis.eligiblePortions(basis)).isEmpty();
    }
    @Test void bundlePositiveParentAndLeafCannotBothContributeToTheBasis() {
        var a = unit("L", null, "0", "1", 100); var b = unit("L", "CHILD", "0", "1", 50);
        var parent = new UnitPolicy(PricingPortion.from(a), true, ScopeStatus.OPEN, "BUNDLE", PricingRole.PARENT);
        var leaf = new UnitPolicy(PricingPortion.from(b), true, ScopeStatus.OPEN, "BUNDLE", PricingRole.LEAF);
        assertThatThrownBy(() -> PricingBenefitBasis.freeze(facts(a, b), List.of(parent, leaf)))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void policyInputOrderDoesNotChangeTheSourceOrderedDigestButSourceVersionDoes() {
        var a = unit("L1", null, "0", "1", 100); var b = unit("L2", null, "0", "1", 50);
        var first = PricingBenefitBasis.freeze(facts(a, b), List.of(policy(a, true), policy(b, false)));
        var same = PricingBenefitBasis.freeze(facts(a, b), List.of(policy(b, false), policy(a, true)));
        assertThat(first).isEqualTo(same);
        var version = new PricingFacts("AED", "a".repeat(64), 8, List.of(a, b));
        assertThat(PricingBenefitBasis.freeze(version, List.of(policy(a, true), policy(b, false))).snapshotHash())
                .isNotEqualTo(first.snapshotHash());
    }
    @Test void tamperedTotalsEligibilityAndDigestCannotPassValidation() {
        var a = unit("L", null, "0", "1", 100);
        var basis = PricingBenefitBasis.freeze(facts(a), List.of(policy(a, true)));
        var tampered = new Snapshot(1, basis.currency(), basis.sourceSnapshotHash(), basis.sourceVersion(),
                100, 99, 1, basis.entries(), basis.snapshotHash());
        assertThatThrownBy(() -> PricingBenefitBasis.validate(tampered)).isInstanceOf(IllegalArgumentException.class);
        var changed = new Snapshot(1, basis.currency(), basis.sourceSnapshotHash(), basis.sourceVersion(),
                100, 100, 0, List.of(new Entry(a, policy(a, false))), basis.snapshotHash());
        assertThatThrownBy(() -> PricingBenefitBasis.validate(changed)).isInstanceOf(IllegalArgumentException.class);
        var hash = new Snapshot(1, basis.currency(), basis.sourceSnapshotHash(), basis.sourceVersion(),
                100, 100, 0, basis.entries(), "b".repeat(64));
        assertThatThrownBy(() -> PricingBenefitBasis.validate(hash)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void sourceAmountsRemainJsonSafeAndUnmodifiable() {
        var a = unit("L", null, "0", "1", MoneyMinor.MAX_JSON_SAFE_MINOR);
        var policies = new ArrayList<>(List.of(policy(a, true)));
        var basis = PricingBenefitBasis.freeze(facts(a), policies); policies.clear();
        assertThat(basis.eligibleMinor()).isEqualTo(MoneyMinor.MAX_JSON_SAFE_MINOR);
        assertThatThrownBy(() -> basis.entries().clear()).isInstanceOf(UnsupportedOperationException.class);
        var b = unit("L2", null, "0", "1", 1);
        assertThatThrownBy(() -> facts(a, b)).isInstanceOf(IllegalArgumentException.class);
    }
}
