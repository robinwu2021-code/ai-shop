package ai.neargo.shop.beauty.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.idem.IdempotencyScope;
import ai.neargo.shop.spi.resource.ResourcePlanContract;
import ai.neargo.shop.spi.resource.ResourcePlanContract.Demand;
import ai.neargo.shop.spi.resource.ResourcePlanContract.FrozenPlan;
import ai.neargo.shop.spi.resource.ResourcePlanContract.Interval;
import ai.neargo.shop.spi.resource.ResourcePlanContract.Qualification;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Independent beauty compilation consumes the real shared contract, without a merchant dependency. */
class ResourcePlanContractTest {
    private static final Instant START = Instant.parse("2026-10-21T10:00:00Z");

    @Test
    void preservesTechnicianQualificationAndBedPreparationServiceCleanup() {
        var service = new Interval(START, START.plusSeconds(3600));
        var bed = List.of(new Interval(START.minusSeconds(600), START), service,
                new Interval(START.plusSeconds(3600), START.plusSeconds(4200)));
        var demands = List.of(new Demand("STAFF", "ITEM-1-TECH", 1, List.of(service)),
                new Demand("BED", "ITEM-1-BED", 1, bed));
        var qualifications = List.of(new Qualification("STAFF", "ITEM-1-TECH", "GOODS", "SKU", true));
        String frozen = ResourcePlanContract.encode(demands, qualifications);
        assertThat(ResourcePlanContract.decode(frozen)).isEqualTo(new FrozenPlan(demands, qualifications));
        assertThat(ResourcePlanContract.hash(frozen))
                .isEqualTo(IdempotencyScope.requestHash("RESOURCE_PLAN_V1", frozen));
        assertThat(ResourcePlanContract.decode(frozen).demands().get(1).intervals()).containsExactlyElementsOf(bed);
        assertThatThrownBy(() -> Class.forName("ai.neargo.shop.merchant.policy.ResourcePlanCodec"))
                .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    void freezingDoesNotRetainMutableIndustryCollectionsOrInventPolicies() {
        var intervals = new ArrayList<>(List.of(new Interval(START, START.plusSeconds(600))));
        var demands = new ArrayList<>(List.of(new Demand("STAFF", "ITEM", 1, intervals)));
        var policies = new ArrayList<>(List.of(new Qualification("STAFF", "ITEM", null, null, false)));
        var snapshot = new FrozenPlan(demands, policies);
        intervals.clear();
        demands.clear();
        policies.clear();
        assertThat(snapshot.demands()).hasSize(1);
        assertThat(snapshot.demands().getFirst().intervals()).hasSize(1);
        assertThat(snapshot.qualifications()).containsExactly(new Qualification("STAFF", "ITEM", null, null, false));
        assertThatThrownBy(snapshot.demands()::clear).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(snapshot.demands().getFirst().intervals()::clear)
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
