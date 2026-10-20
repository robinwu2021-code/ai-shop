package ai.neargo.shop.pay.storedvalue.policy;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.spi.entitlement.EntitlementDefinition.RedemptionScope;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.UseItem;
import ai.neargo.shop.spi.entitlement.EntitlementUseSourcePort.UseSourceFact;
import ai.neargo.shop.spi.entitlement.SourceRef;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StoredValueAllocationPolicyTest {
    private final StoredValueAllocationPolicy policy = new StoredValueAllocationPolicy();
    private UseSourceFact source(List<UseItem> items) {
        return new UseSourceFact("MAIN", new SourceRef("CORE", "SERVICE_USE", "ES1", null, null, null, 0),
                "U1", "M1", "S1", "CNY", items, "a".repeat(64), "Beauty", null, null, 0,
                Map.of("L1", List.of("CAT_A"), "L2", List.of("CAT_B")), Instant.EPOCH);
    }
    private StoredValueAllocationPolicy.LotBalance lot(String no, long p, long b, String mode,
                                                         List<String> refs, long age) {
        return new StoredValueAllocationPolicy.LotBalance(no, p, b, new RedemptionScope(mode, refs),
                Instant.EPOCH.plusSeconds(age));
    }
    @Test void genericOldLotDoesNotStarveARestrictedLotAndLeavesEveryServiceLineCovered() {
        var source = source(List.of(new UseItem("L1", "G1", "Care A", 1, 100),
                new UseItem("L2", "G2", "Care B", 1, 100)));
        var generic = lot("OLD", 100, 0, "ALL", List.of(), 1);
        var restricted = lot("NEW", 100, 0, "GOODS", List.of("G1"), 2);
        var plan = policy.allocate(List.of(restricted, generic), source);
        assertThat(plan.amountMinor()).isEqualTo(200);
        assertThat(plan.allocations()).hasSize(2);
        assertThat(plan.allocations().getFirst().lotNo()).isEqualTo("OLD");
        assertThat(plan.allocations().getFirst().items()).containsExactly(
                new StoredValueAllocationPolicy.ItemAllocation("L2", 100));
        assertThat(plan.allocations().getLast().items()).containsExactly(
                new StoredValueAllocationPolicy.ItemAllocation("L1", 100));
    }
    @Test void frozenProportionUsesIntegerMoneyAndCanExhaustTheEntireResidualWithoutOverflow() {
        var balance = lot("A", 1000, 100, "STORES", List.of("S1"), 1);
        var part = policy.allocate(List.of(balance), source(List.of(new UseItem("L1", "G1", "Care", 1, 100))));
        assertThat(part.allocations().getFirst().principalMinor()).isEqualTo(91);
        assertThat(part.allocations().getFirst().bonusMinor()).isEqualTo(9);
        var all = policy.allocate(List.of(balance), source(List.of(new UseItem("L1", "G1", "Care", 1, 1100))));
        assertThat(all.allocations().getFirst().principalMinor()).isEqualTo(1000);
        assertThat(all.allocations().getFirst().bonusMinor()).isEqualTo(100);
        var enormous = policy.allocate(List.of(lot("MAX", Long.MAX_VALUE - 1000, 1000, "ALL", List.of(), 1)),
                source(List.of(new UseItem("L1", "G1", "Care", 1, Long.MAX_VALUE))));
        assertThat(enormous.amountMinor()).isEqualTo(Long.MAX_VALUE);
        assertThat(enormous.allocations().getFirst().principalMinor()).isEqualTo(Long.MAX_VALUE - 1000);
        assertThat(enormous.allocations().getFirst().bonusMinor()).isEqualTo(1000);
    }
    @Test void storeAndCategoryRestrictionsCannotBeAddedToAnUnrelatedServiceOrInventAFullPayment() {
        var source = source(List.of(new UseItem("L1", "G1", "Care A", 1, 100),
                new UseItem("L2", "G2", "Care B", 1, 100)));
        var plan = policy.allocate(List.of(lot("OTHER_STORE", 1000, 0, "STORES", List.of("OTHER"), 1),
                lot("CATEGORY_A", 50, 0, "CATEGORIES", List.of("CAT_A"), 2)), source);
        assertThat(plan.amountMinor()).isEqualTo(50);
        assertThat(plan.allocations()).hasSize(1);
        assertThat(plan.allocations().getFirst().items()).containsExactly(
                new StoredValueAllocationPolicy.ItemAllocation("L1", 50));
    }
}
