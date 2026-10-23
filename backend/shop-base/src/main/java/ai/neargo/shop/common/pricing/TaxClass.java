package ai.neargo.shop.common.pricing;

/** Tax eligibility is independent of participation in promotions; unknown is never zero-rated. */
public enum TaxClass { STANDARD, ZERO_RATED, EXEMPT, OUT_OF_SCOPE, UNKNOWN }
