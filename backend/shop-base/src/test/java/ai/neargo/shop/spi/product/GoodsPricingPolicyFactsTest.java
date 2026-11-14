package ai.neargo.shop.spi.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.pricing.TaxClass;
import ai.neargo.shop.spi.product.GoodsPricingPolicyPort.Policy;
import ai.neargo.shop.spi.product.GoodsPricingPolicyPort.Scope;
import ai.neargo.shop.spi.product.GoodsPricingPolicyPort.SkuRef;
import ai.neargo.shop.spi.product.GoodsPricingPolicyPort.Snapshot;
import java.util.List;
import org.junit.jupiter.api.Test;

class GoodsPricingPolicyFactsTest {
    private final Scope scope = new Scope("MAIN", "E", "S");
    private Policy policy(String sku, long version, boolean eligible, TaxClass tax) {
        return new Policy("P" + sku, "G", sku, version, eligible, tax);
    }
    @Test void canonicalPolicyOrderIsStableButActualVersionsAndContentsAreBound() {
        var a = policy("A", 1, true, TaxClass.STANDARD); var b = policy("B", 1, false, TaxClass.EXEMPT);
        var first = GoodsPricingPolicyFacts.freeze(scope, List.of(a, b));
        assertThat(GoodsPricingPolicyFacts.freeze(scope, List.of(b, a))).isEqualTo(first);
        assertThat(GoodsPricingPolicyFacts.freeze(scope, List.of(policy("A", 2, true, TaxClass.STANDARD), b)))
                .isNotEqualTo(first);
        assertThat(GoodsPricingPolicyFacts.freeze(scope, List.of(policy("A", 1, false, TaxClass.STANDARD), b)))
                .isNotEqualTo(first);
    }
    @Test void absentOrUnknownPolicyCannotBecomeAZeroRatedOrEligibleDefault() {
        assertThatThrownBy(() -> GoodsPricingPolicyFacts.freeze(scope, List.of())).isInstanceOf(RuntimeException.class);
        for (var tax : new TaxClass[] {null, TaxClass.UNKNOWN}) {
            assertThatThrownBy(() -> GoodsPricingPolicyFacts.freeze(scope, List.of(policy("A", 1, true, tax))))
                    .isInstanceOf(RuntimeException.class);
        }
        assertThatThrownBy(() -> GoodsPricingPolicyFacts.freeze(scope,
                List.of(policy("A", 0, true, TaxClass.STANDARD)))).isInstanceOf(RuntimeException.class);
    }
    @Test void exactScopeAndCompleteGoodsSkuSetAreRequiredEvenWithAValidDigest() {
        var ref = List.of(new SkuRef("G", "A"));
        var value = GoodsPricingPolicyFacts.freeze(scope, List.of(policy("A", 1, true, TaxClass.STANDARD)));
        GoodsPricingPolicyFacts.validate(scope, ref, value);
        assertThatThrownBy(() -> GoodsPricingPolicyFacts.validate(new Scope("MAIN", "OTHER", "S"), ref, value))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> GoodsPricingPolicyFacts.validate(scope, List.of(new SkuRef("WRONG", "A")), value))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> GoodsPricingPolicyFacts.validate(scope, List.of(new SkuRef("G", "B")), value))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> GoodsPricingPolicyFacts.validate(scope, ref,
                new Snapshot(1, scope, value.policies(), "0".repeat(64)))).isInstanceOf(RuntimeException.class);
    }
    @Test void duplicateSkuConflictingOwnersAndUnsafeIdentitiesAreRejected() {
        var p = policy("A", 1, true, TaxClass.STANDARD);
        assertThatThrownBy(() -> GoodsPricingPolicyFacts.freeze(scope, List.of(p, p)))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> GoodsPricingPolicyFacts.request(scope,
                List.of(new SkuRef("G", "A"), new SkuRef("OTHER", "A")))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new Scope("OTHER", "E", "S")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new SkuRef(" G", "A")).isInstanceOf(RuntimeException.class);
    }
}
