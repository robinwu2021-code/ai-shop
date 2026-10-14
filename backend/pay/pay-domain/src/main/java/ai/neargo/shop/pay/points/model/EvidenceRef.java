package ai.neargo.shop.pay.points.model;

/** A reference to separately verified real money, consumed once by its voucher. */
public record EvidenceRef(String evidenceNo, String currency, long externalMinor) { }
