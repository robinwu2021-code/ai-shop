package ai.neargo.shop.common.promotion;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Shared minor-unit arithmetic for legacy adapters and pay; policy eligibility remains with each owner. */
public final class CouponCalculation {
    private static final BigInteger BP = BigInteger.valueOf(10000);
    private CouponCalculation() { }

    /** Percent means payable basis points; monetary discount is floored, matching the historical contract. */
    public static long discount(String mode, Long value, Long cap, long base) {
        if (base < 0) throw new IllegalArgumentException("Negative coupon base");
        long benefit = value == null ? 0 : value;
        if ("CASH".equals(mode)) return Math.min(benefit, base);
        if (!"PERCENT".equals(mode)) return 0;
        if (benefit < 0 || benefit > 10000) throw new IllegalArgumentException("Invalid payable basis points");
        long off = BigInteger.valueOf(base).multiply(BigInteger.valueOf(10000 - benefit)).divide(BP).longValueExact();
        return cap != null && cap > 0 ? Math.min(off, cap) : off;
    }

    /** Rounded proportional shares; remainder targets the largest base, bounded by each line's amount. */
    public static List<Long> allocate(long discount, List<Long> bases) {
        if (discount < 0 || bases == null || bases.stream().anyMatch(n -> n == null || n < 0)) {
            throw new IllegalArgumentException("Invalid coupon allocation");
        }
        long base = 0;
        for (long n : bases) base = Math.addExact(base, n);
        if (discount > base || base == 0 && discount != 0) throw new IllegalArgumentException("Discount exceeds base");
        var parts = new ArrayList<Long>();
        var total = BigInteger.ZERO;
        for (long n : bases) {
            var numerator = BigInteger.valueOf(discount).multiply(BigInteger.valueOf(n));
            var qr = base == 0 ? new BigInteger[]{BigInteger.ZERO, BigInteger.ZERO}
                    : numerator.divideAndRemainder(BigInteger.valueOf(base));
            long part = qr[0].longValueExact();
            if (base != 0 && qr[1].multiply(BigInteger.TWO).compareTo(BigInteger.valueOf(base)) >= 0) part++;
            parts.add(part);
            total = total.add(BigInteger.valueOf(part));
        }
        long remainder = BigInteger.valueOf(discount).subtract(total).longValueExact();
        var order = new ArrayList<Integer>();
        for (int i = 0; i < bases.size(); i++) order.add(i);
        order.sort(Comparator.<Integer>comparingLong(bases::get).reversed().thenComparingInt(i -> i));
        for (int i : order) {
            long adjustment = remainder > 0 ? Math.min(remainder, bases.get(i) - parts.get(i))
                    : -Math.min(-remainder, parts.get(i));
            parts.set(i, parts.get(i) + adjustment);
            remainder -= adjustment;
            if (remainder == 0) break;
        }
        if (remainder != 0) throw new IllegalStateException("Allocation did not conserve discount");
        return List.copyOf(parts);
    }
}
