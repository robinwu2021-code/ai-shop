package ai.neargo.commerce.api.catalog;

/**
 * Null version/component identity requests server generation; non-null identifies an existing
 * draft/component.
 */
public record SkuDraft(
        String productNo,
        String skuNo,
        String skuVersionNo,
        String title,
        ai.neargo.commerce.api.common.Money listPrice,
        ai.neargo.commerce.api.common.Money price,
        java.util.List<String> allowedBusinessTypes,
        String refundPolicySnapshot,
        java.util.List<SkuComponent> components,
        int maxPerOrder,
        long expectedVersion,
        String reason) {
    public SkuDraft {
        allowedBusinessTypes = java.util.List.copyOf(allowedBusinessTypes);
        components = java.util.List.copyOf(components);
    }
}
