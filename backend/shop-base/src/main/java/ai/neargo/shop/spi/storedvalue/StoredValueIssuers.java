package ai.neargo.shop.spi.storedvalue;

import java.util.List;
import java.util.Set;

/** Core resolves these authority facts before entering a Pay transaction; never accept them from a public request. */
public final class StoredValueIssuers {
    private StoredValueIssuers() { }

    /** Trusted existing store scope resolved by Core; never a public request body or a brand finance grant. */
    public record StoreAuthority(String tenantNo,String actorNo,String entityNo,String storeNo,String storeName,long storeRevision) {
        public StoreAuthority {
            ai.neargo.shop.spi.entitlement.ContractChecks.id(tenantNo);
            ai.neargo.shop.spi.entitlement.ContractChecks.id(actorNo);
            ai.neargo.shop.spi.entitlement.ContractChecks.id(entityNo);
            ai.neargo.shop.spi.entitlement.ContractChecks.id(storeNo);
            ai.neargo.shop.spi.entitlement.ContractChecks.require(storeName!=null&&!storeName.isBlank()&&storeName.length()<=128&&storeRevision>=0);
        }
    }

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
            String issuerVersionNo, long revision, String settlementEntityNo, String currency, List<Route> routes, String collectionMode,String clearingMode,String debitAllocationMode,String marketZone) {
        public View(String issuerNo,String scopeType,String scopeNo,String name,String status,String version,long revision,String entity,String currency,List<Route> routes){this(issuerNo,scopeType,scopeNo,name,status,version,revision,entity,currency,routes,"STORE_LOCAL","INTERNAL_RECON","LEGACY_SNAPSHOT","Asia/Shanghai");}
        public View { routes = List.copyOf(routes); }
    }

    public record AccessAck(String issuerNo,String storeNo,long sourceRevision,String status) {}
    public record ScopeRef(String scopeType,String scopeNo) {}
    public record StoreQuery(String tenantNo,String actorNo,String entityNo,String storeNo) {
        public StoreQuery { for(String value:List.of(tenantNo,actorNo,entityNo,storeNo))ai.neargo.shop.spi.entitlement.ContractChecks.id(value); }
    }
    public record StoreOption(String issuerNo,String scopeType,String scopeNo,String name,String currency,String status,String writeMode,boolean canRecharge,boolean canRedeem,String unavailableReason) {}
    public record StoreOptions(String storeNo,boolean legacyMode,List<StoreOption> issuers) { public StoreOptions{issuers=List.copyOf(issuers);} }
    public record ConfigurationImpact(boolean allowed,long affectedAccountCount,boolean hasPending,List<String> blockers) {public ConfigurationImpact{blockers=List.copyOf(blockers);}}
    public record Lookup(View issuer) { }

    /** Preview of an authorized route, not evidence that money was collected. */
    public record CollectionRoute(String issuerNo, String issuerVersionNo, Route route) { }
}
