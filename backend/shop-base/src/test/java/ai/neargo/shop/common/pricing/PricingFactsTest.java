package ai.neargo.shop.common.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PricingFactsTest {
    private static final String SOURCE_HASH = "a".repeat(64);

    @Test
    void copiesSourceUnitsAndPreservesAuthoritativeTotals() {
        List<PricingUnit> source = new ArrayList<>(List.of(unit("L1", null, "0", "1", 100)));
        PricingFacts facts = new PricingFacts("AED", SOURCE_HASH, 0, source);
        source.clear();
        assertThat(facts.units()).hasSize(1);
        assertThat(facts.baseTotal()).isEqualTo(new MoneyMinor("AED", 100));
        assertThatThrownBy(() -> facts.units().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsDuplicateAndOverlappingPortionsOfTheSameComponent() {
        PricingUnit first = unit("L1", null, "0", "1", 100);
        assertThatThrownBy(() -> facts(first, first)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> facts(unit("L1", null, "0.5", "1.5", 100), first))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> facts(unit("L1", "ADDON", "0", "2", 100),
                unit("L1", "ADDON", "1", "2", 100))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsAdjacentPortionsAndIndependentComponentsWithoutReorderingSource() {
        PricingUnit later = unit("L1", null, "1", "2", 100);
        PricingUnit earlier = unit("L1", null, "0", "1", 100);
        PricingUnit addon = unit("L1", "ADDON", "0", "2", 50);
        PricingFacts facts = facts(later, earlier, addon);
        assertThat(facts.units()).containsExactly(later, earlier, addon);
        assertThat(facts.baseTotal()).isEqualTo(new MoneyMinor("AED", 250));
    }

    @Test
    void rejectsMixedCurrenciesAndUnsafeAggregateAmounts() {
        PricingUnit cny = new PricingUnit("L1", null, "G1", "S1", "V1", "INVOICE-L1",
                BigDecimal.ZERO, BigDecimal.ONE, new MoneyMinor("CNY", 100), new MoneyMinor("CNY", 100));
        assertThatThrownBy(() -> facts(cny)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> facts(unit("L1", null, "0", "1", 9_007_199_254_740_991L),
                unit("L2", null, "0", "1", 1))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requiresAnExplicitSourceSnapshotVersionAndNonEmptyUnits() {
        List<PricingUnit> units = List.of(unit("L1", null, "0", "1", 100));
        assertThatThrownBy(() -> new PricingFacts("AED", "not-a-hash", 0, units))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PricingFacts("AED", SOURCE_HASH, -1, units))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PricingFacts("AED", SOURCE_HASH, 0, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static PricingFacts facts(PricingUnit... units) {
        return new PricingFacts("AED", SOURCE_HASH, 3, List.of(units));
    }

    private static PricingUnit unit(String line, String component, String start, String end, long minor) {
        return new PricingUnit(line, component, "G1", "S1", "V1", "INVOICE-" + line,
                new BigDecimal(start), new BigDecimal(end), new MoneyMinor("AED", minor), new MoneyMinor("AED", minor));
    }
}
