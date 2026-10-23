package ai.neargo.shop.spi.storedvalue;

import java.util.List;
import java.util.Set;

/** Core resolves these authority facts before entering a Pay transaction; never accept them from a public request. */
public final class StoredValueIssuers {
    private StoredValueIssuers() { }

    public record Route(String paymentMerchantNo, String entityNo, String storeNo, String channel, String scene) { }

    public record AuthorizedScope(String tenantNo, String actorNo, String managementNo, String scopeType,
            String scopeNo, long authorityRevision, String operatingEntityNo,
            Set<String> authorizedSettlementEntities, List<Route> authorizedRoutes) {
        public AuthorizedScope {
            authorizedSettlementEntities = Set.copyOf(authorizedSettlementEntities);
            authorizedRoutes = List.copyOf(authorizedRoutes);
        }
    }

    public record Config(String name, String settlementEntityNo, String collectionMode, String clearingMode,
            String debitAllocationMode, String currency, String marketZone, List<Route> routes, long expectedRevision) {
        public Config { routes = List.copyOf(routes); }
    }

    public record View(String issuerNo, String scopeType, String scopeNo, String name, String status,
            String issuerVersionNo, long revision, String settlementEntityNo, String currency, List<Route> routes) {
        public View { routes = List.copyOf(routes); }
    }

    public record Lookup(View issuer) { }

    /** Preview of an authorized route, not evidence that money was collected. */
    public record CollectionRoute(String issuerNo, String issuerVersionNo, Route route) { }
}
