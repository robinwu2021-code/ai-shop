package ai.neargo.commerce.api.catalog;

public record ProductDraft(
        String productNo,
        String categoryCode,
        ProductKind kind,
        String name,
        String description,
        java.util.List<String> allowedBusinessTypes,
        int sortOrder,
        long expectedVersion,
        String reason) {
    public ProductDraft {
        allowedBusinessTypes = java.util.List.copyOf(allowedBusinessTypes);
    }
}
