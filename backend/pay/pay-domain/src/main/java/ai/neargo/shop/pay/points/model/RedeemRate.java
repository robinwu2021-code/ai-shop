package ai.neargo.shop.pay.points.model;

import java.math.BigInteger;

/** Reduced integer units prevent rounding from creating unsupported points or money. */
public record RedeemRate(long pointsUnit, long minorUnit) {
    public RedeemRate {
        if (pointsUnit <= 0 || minorUnit <= 0) {
            throw new IllegalArgumentException("Rate units must be positive");
        }
        long divisor = BigInteger.valueOf(pointsUnit).gcd(BigInteger.valueOf(minorUnit)).longValueExact();
        pointsUnit /= divisor;
        minorUnit /= divisor;
    }

    /** Converts signed, aligned ledger changes exactly; overflow fails before any write. */
    public long toMinor(long points) {
        requireAligned(points);
        return BigInteger.valueOf(points).divide(BigInteger.valueOf(pointsUnit))
                .multiply(BigInteger.valueOf(minorUnit)).longValueExact();
    }

    /** Floors a nonnegative quote to a redeemable unit. */
    public long floorAligned(long points) {
        if (points < 0) {
            throw new IllegalArgumentException("Quote points must be nonnegative");
        }
        return points - points % pointsUnit;
    }

    /** Floors a money budget before converting, so a partial unit cannot be spent. */
    public long pointsForMinor(long minor) {
        if (minor < 0) {
            throw new IllegalArgumentException("Budget must be nonnegative");
        }
        return BigInteger.valueOf(minor).divide(BigInteger.valueOf(minorUnit))
                .multiply(BigInteger.valueOf(pointsUnit)).longValueExact();
    }

    /** Both debits and credits must use the same exact unit. */
    public void requireAligned(long points) {
        if (points % pointsUnit != 0) {
            throw new IllegalArgumentException("Unaligned points");
        }
    }
}
