package ai.neargo.shop.merchant.dto;

/** Canonical live parents used by scoped journal SQL; never a partially populated persistence entity. */
public record ResourceCommandScopeRow(String tenantNo, String entityNo, String storeNo) {
}
