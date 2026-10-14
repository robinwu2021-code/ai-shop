package ai.neargo.shop.pay.points.model;

/** Receivables are not present in this real-funds snapshot. */
public record FundPool(String typeCode, String currency, long u, long c, long d, long b, long reservedBMinor, long lockedUMinor, long lockedBMinor) {
    /** Issuance and internal transfers cannot use a pending withdrawal. */
    public long freeU() { return Math.subtractExact(u,lockedUMinor); }
    /** Already promised refund money is never available for another hold or withdrawal. */
    public long freeB() { return Math.subtractExact(Math.subtractExact(b, reservedBMinor),lockedBMinor); }
}
