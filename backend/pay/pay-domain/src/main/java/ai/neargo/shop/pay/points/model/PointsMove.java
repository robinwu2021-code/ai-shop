package ai.neargo.shop.pay.points.model;

/** Signed movements, rather than replacement balances, preserve the audit trail. */
public record PointsMove(AccountKey account, long deltaPoints) { }
