package ai.neargo.shop.promotion.cost;

import java.math.BigInteger;

/** Exact headquarters and store cost allocation, independent of current promotion rules. */
public final class PromotionCostShares {
    private static final BigInteger BASIS_POINT_DENOMINATOR = BigInteger.valueOf(10000);

    private PromotionCostShares() {}

    /** Carries signed cost shares; refunds use negative values to reverse the original allocation. */
    public record Shares(long headquartersMinor, long storeMinor) {
        /** Returns both shares exactly, reporting overflow instead of silently changing the total. */
        public long totalMinor() {
            return Math.addExact(headquartersMinor, storeMinor);
        }
    }

    /** Splits a nonnegative amount by the frozen headquarters and store basis-point ratio. */
    public static Shares allocate(long totalMinor, int headquartersBasisPoints, int storeBasisPoints) {
        if (totalMinor < 0 || headquartersBasisPoints < 0 || headquartersBasisPoints > 10000
                || storeBasisPoints < 0 || storeBasisPoints > 10000
                || headquartersBasisPoints + storeBasisPoints != 10000) {
            throw new IllegalArgumentException("invalid promotion cost allocation");
        }
        long headquartersMinor = BigInteger.valueOf(totalMinor)
                .multiply(BigInteger.valueOf(headquartersBasisPoints))
                .divide(BASIS_POINT_DENOMINATOR)
                .longValueExact();
        return new Shares(headquartersMinor, totalMinor - headquartersMinor);
    }

    /** Reverses the incremental refund using the original shares and cumulative refund rounding. */
    public static Shares reverse(Shares original, long refundedBefore, long refundedAfter) {
        if (original == null || original.headquartersMinor() < 0 || original.storeMinor() < 0) {
            throw new IllegalArgumentException("invalid original promotion cost shares");
        }
        long originalTotal;
        try {
            originalTotal = original.totalMinor();
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("original promotion cost total overflows", overflow);
        }
        if (refundedBefore < 0 || refundedAfter < refundedBefore || refundedAfter > originalTotal) {
            throw new IllegalArgumentException("invalid cumulative refund amount");
        }
        if (originalTotal == 0) {
            return new Shares(0, 0);
        }

        long headquartersBefore = cumulativeHeadquartersRefund(original.headquartersMinor(), refundedBefore,
                originalTotal);
        long headquartersAfter = cumulativeHeadquartersRefund(original.headquartersMinor(), refundedAfter,
                originalTotal);
        long headquartersReversal = -(headquartersAfter - headquartersBefore);
        long storeReversal = -(refundedAfter - refundedBefore) - headquartersReversal;
        return new Shares(headquartersReversal, storeReversal);
    }

    private static long cumulativeHeadquartersRefund(long headquartersMinor, long refundedMinor, long originalTotal) {
        return BigInteger.valueOf(headquartersMinor)
                .multiply(BigInteger.valueOf(refundedMinor))
                .divide(BigInteger.valueOf(originalTotal))
                .longValueExact();
    }
}
