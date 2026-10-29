package ai.neargo.shop.beauty.dto;

/** Canonical identity is a scoped reference, never a customer credential or payment buyer assertion. */
public record CustomerIdentityVO(String kind, String reference) { }
