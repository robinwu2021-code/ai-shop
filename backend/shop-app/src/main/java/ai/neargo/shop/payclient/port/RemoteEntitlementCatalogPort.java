package ai.neargo.shop.payclient.port;

import ai.neargo.shop.pay.client.PayInternalApi;
import ai.neargo.shop.payclient.RemoteEntitlementCalls;
import ai.neargo.shop.pay.client.PayEntitlementRequests.CatalogVersion;
import ai.neargo.shop.pay.client.PayEntitlementRequests.ValidateCatalogReference;
import ai.neargo.shop.spi.entitlement.EntitlementCatalogPort;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Read-only catalog SPI routes remotely; issuance/use RPCs are separate rollout gates. */
@Component
@ConditionalOnProperty(name = "shop.pay.deployment", havingValue = "standalone")
public class RemoteEntitlementCatalogPort implements EntitlementCatalogPort {
    private final PayInternalApi pay;
    public RemoteEntitlementCatalogPort(PayInternalApi pay) { this.pay = pay; }
    @Override
    public PublishedDefinition getPublishedVersion(String tenantNo, String entityNo, String versionNo) {
        return RemoteEntitlementCalls.call(() -> pay.entVersion(new CatalogVersion(context(tenantNo),
                entityNo, versionNo)));
    }
    @Override
    public PublishedDefinition validateReference(ReferenceQuery query) {
        return RemoteEntitlementCalls.call(() -> pay.entReference(new ValidateCatalogReference(
                context(query.tenantNo()), query)));
    }
    private EntitlementContext context(String tenantNo) {
        // Reads have no command dedup identity; authenticated service is the actor.
        return new EntitlementContext(tenantNo, "SYSTEM", UUID.randomUUID().toString(), "0".repeat(64));
    }
}
