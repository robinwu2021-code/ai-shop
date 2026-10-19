package ai.neargo.shop.pay.points.model;

/** Immutable money dimensions survive activation; status controls only future promises. */
public record PointType(String typeCode, String market, String currency, int scale, RedeemRate rate,
                        int deductRatioBp, int idleDays, Status status) {
    public enum Status { DRAFT, ACTIVE, DRAINING, RETIRED }
}
