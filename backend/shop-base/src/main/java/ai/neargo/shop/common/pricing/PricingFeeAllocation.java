package ai.neargo.shop.common.pricing;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/** Exact fee allocation allows a fee greater than the original goods basis. */
public final class PricingFeeAllocation {
    private PricingFeeAllocation() {}

    public static List<Long> allocate(long amount, List<Long> originalWeights) {
        if (amount < 0
                || originalWeights == null
                || originalWeights.isEmpty()
                || originalWeights.stream().anyMatch(v -> v == null || v < 0))
            throw new IllegalArgumentException("Invalid fee allocation");
        var weights = new ArrayList<>(originalWeights);
        BigInteger sum =
                weights.stream().map(BigInteger::valueOf).reduce(BigInteger.ZERO, BigInteger::add);
        if (sum.signum() == 0) {
            weights.replaceAll(v -> 1L);
            sum = BigInteger.valueOf(weights.size());
        }
        var result = new ArrayList<Long>();
        var remainders = new ArrayList<BigInteger>();
        long allocated = 0;
        for (long weight : weights) {
            var parts =
                    BigInteger.valueOf(amount)
                            .multiply(BigInteger.valueOf(weight))
                            .divideAndRemainder(sum);
            long value = parts[0].longValueExact();
            result.add(value);
            remainders.add(parts[1]);
            allocated = Math.addExact(allocated, value);
        }
        var order = new ArrayList<Integer>();
        for (int i = 0; i < weights.size(); i++) order.add(i);
        order.sort(
                (a, b) -> {
                    int c = remainders.get(b).compareTo(remainders.get(a));
                    return c == 0 ? Integer.compare(a, b) : c;
                });
        for (int i = 0; i < amount - allocated; i++) {
            int index = order.get(i);
            result.set(index, result.get(index) + 1);
        }
        return List.copyOf(result);
    }
}
