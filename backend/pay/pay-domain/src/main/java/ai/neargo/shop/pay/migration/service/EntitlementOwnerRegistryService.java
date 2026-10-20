package ai.neargo.shop.pay.migration.service;

import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.SourceIdentity;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.OwnerRef;

/** Registration records an unchanged legacy owner; migration activation is a separate controlled protocol. */
public interface EntitlementOwnerRegistryService {
    OwnerRef registerLegacy(CatalogScope operator,SourceIdentity source,String migrationBatchNo);
    OwnerRef resolve(EntitlementContext context,SourceIdentity source);
}
