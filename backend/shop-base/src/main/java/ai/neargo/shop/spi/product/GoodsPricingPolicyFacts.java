package ai.neargo.shop.spi.product;

import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.common.pricing.TaxClass;
import ai.neargo.shop.spi.product.GoodsPricingPolicyPort.Policy;
import ai.neargo.shop.spi.product.GoodsPricingPolicyPort.Scope;
import ai.neargo.shop.spi.product.GoodsPricingPolicyPort.SkuRef;
import ai.neargo.shop.spi.product.GoodsPricingPolicyPort.Snapshot;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Strict complete-set receipt validation. A digest never grants permission to configure or transact. */
public final class GoodsPricingPolicyFacts {
    private GoodsPricingPolicyFacts() { }
    public static List<SkuRef> request(Scope scope, List<SkuRef> skus) {
        Objects.requireNonNull(scope);
        require(skus != null && !skus.isEmpty() && skus.size() <= 512);
        var seen = new HashSet<String>();
        for (var sku : skus) { require(sku != null && seen.add(sku.skuNo())); }
        return skus.stream().sorted(Comparator.comparing(SkuRef::skuNo)).toList();
    }
    public static Snapshot freeze(Scope scope, List<Policy> policies) {
        Objects.requireNonNull(scope);
        require(policies != null && !policies.isEmpty() && policies.size() <= 512);
        var numbers = new HashSet<String>(); var identities = new HashSet<String>();
        for (var policy : policies) {
            Objects.requireNonNull(policy); key(policy.policyNo()); key(policy.goodsNo()); key(policy.skuNo());
            require(numbers.add(policy.policyNo()) && identities.add(policy.skuNo())
                    && policy.version() >= 1 && policy.version() <= 9007199254740991L
                    && policy.taxClass() != null && policy.taxClass() != TaxClass.UNKNOWN);
        }
        var sorted = policies.stream().sorted(Comparator.comparing(Policy::skuNo)).toList();
        return new Snapshot(1, scope, sorted,
                PricingSnapshotHash.of(List.of("GOODS_PRICING_POLICY_V1", scope, sorted)));
    }
    public static void validate(Scope scope, List<SkuRef> requested, Snapshot value) {
        var expected = request(scope, requested);
        require(value != null && value.schemaVersion() == 1 && scope.equals(value.scope()));
        var actual = freeze(scope, value.policies());
        require(actual.snapshotHash().equals(value.snapshotHash())
                && actual.policies().stream().map(p -> new SkuRef(p.goodsNo(), p.skuNo())).toList().equals(expected));
    }
    public static void key(String value) {
        require(value != null && !value.isBlank() && value.length() <= 32
                && value.codePoints().noneMatch(c -> Character.isWhitespace(c)
                        || Character.isSpaceChar(c) || Character.isISOControl(c)));
    }
    private static void require(boolean valid) {
        if (!valid) throw new IllegalArgumentException("Explicit complete goods pricing policy required");
    }
}
