package ai.neargo.commerce.api.common;

import java.time.Instant;

/** Trusted provider precision. A reported instant is the inclusive lower bound of its bucket. */
public final class PaymentTimePrecision {
    private PaymentTimePrecision() {}

    public static long normalize(Long precision) {
        long p = precision == null ? 1 : precision;
        if (p != 1 && p != 1000)
            throw new IllegalArgumentException("INVALID_PAYMENT_TIME_PRECISION");
        return p;
    }

    public static long latestMillis(Instant paidAt, Long precision) {
        try {
            return Math.addExact(paidAt.toEpochMilli(), normalize(precision) - 1);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("INVALID_PAYMENT_TIME_RANGE", overflow);
        }
    }
}
