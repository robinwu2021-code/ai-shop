package ai.neargo.shop.pay.points.model;

/** Signed real-fund movement within one voucher's type and currency. */
public record FundMove(FundBucket bucket, long deltaMinor) { }
