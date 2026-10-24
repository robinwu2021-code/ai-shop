package ai.neargo.shop.food.dto;

import java.math.BigDecimal;

/** Frozen charged source units; identical repeated source references are counted once, never rounded to integers. */
public record SalesQuotaClaimSource(String sourceRef, String skuNo, BigDecimal quantity) { }
