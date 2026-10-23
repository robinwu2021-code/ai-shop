package ai.neargo.shop.food.dto;

import java.math.BigDecimal;

/** Stable original claim references for a frozen batch; these quantities are sales limits, not physical inventory. */
public record SalesQuotaClaimVO(String claimNo, String restrictionNo, String batchNo,
                               BigDecimal quantity, String status) { }
