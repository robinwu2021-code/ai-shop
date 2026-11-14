package ai.neargo.commerce.api.catalog;

public record BusinessEndDraft(
        String code,
        String entitlementCode,
        String name,
        EndScope scope,
        java.util.List<String> allowedBusinessTypes,
        ReleaseStatus releaseStatus,
        boolean saleEnabled,
        boolean bootstrapRequired,
        boolean enabled,
        String clientId,
        String routePolicyRef,
        String acceptanceRef,
        long expectedVersion,
        String reason) {
    public BusinessEndDraft {
        allowedBusinessTypes = java.util.List.copyOf(allowedBusinessTypes);
    }
}
