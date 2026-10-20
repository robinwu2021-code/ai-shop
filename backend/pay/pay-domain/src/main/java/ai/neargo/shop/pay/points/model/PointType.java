package ai.neargo.shop.pay.points.model;

/** Immutable money dimensions survive activation; status controls only future promises. */
public record PointType(String typeCode, String name, String market, String currency, int scale, RedeemRate rate,
                        int deductRatioBp, int idleDays, Status status) {
    /** Keeps legacy construction sites source-compatible while new records carry an operator-facing name. */
    public PointType(String typeCode, String market, String currency, int scale, RedeemRate rate,
                     int deductRatioBp, int idleDays, Status status) {
        this(typeCode, typeCode, market, currency, scale, rate, deductRatioBp, idleDays, status);
    }
    public enum Status { DRAFT, ACTIVE, DRAINING, RETIRED }
}
