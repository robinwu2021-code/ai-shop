package ai.neargo.shop.pay.points.model;

/** Immutable money dimensions survive activation; earning pause does not disable redemption. */
public record PointType(String typeCode, String name, String market, String currency, int scale, RedeemRate rate,
                        int deductRatioBp, int idleDays, Status status, boolean earningPaused, boolean orderDefault) {
    public PointType(String typeCode, String name, String market, String currency, int scale, RedeemRate rate,
                     int deductRatioBp, int idleDays, Status status) {
        this(typeCode, name, market, currency, scale, rate, deductRatioBp, idleDays, status, false, false);
    }
    /** Legacy construction sites remain source-compatible. */
    public PointType(String typeCode, String market, String currency, int scale, RedeemRate rate,
                     int deductRatioBp, int idleDays, Status status) {
        this(typeCode, typeCode, market, currency, scale, rate, deductRatioBp, idleDays, status);
    }
    public enum Status { DRAFT, ACTIVE, DRAINING, RETIRED }
}
