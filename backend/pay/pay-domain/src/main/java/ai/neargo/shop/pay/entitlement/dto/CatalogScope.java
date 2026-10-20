package ai.neargo.shop.pay.entitlement.dto;

/** Trusted application context, never bound from a public request body or caller-supplied identity header. */
public record CatalogScope(String tenantNo, String entityNo, String actorNo, boolean platformActor) {
    public CatalogScope {
        require(tenantNo, 32);
        require(entityNo, 64);
        require(actorNo, 64);
    }

    private static void require(String value, int limit) {
        if (value == null || value.isBlank() || value.length() > limit || !value.equals(value.strip())) {
            throw new IllegalArgumentException("Invalid trusted catalog context");
        }
    }
}
