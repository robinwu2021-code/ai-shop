package ai.neargo.shop.pay.entitlement.port;

import ai.neargo.shop.pay.migration.service.EntitlementOwnerRegistryService;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort;

/** Core callers receive only explicit ownership metadata, never a guessed legacy fallback. */
public class EntitlementOwnerRegistryPortImpl implements EntitlementOwnerRegistryPort {
    private final EntitlementOwnerRegistryService service;
    public EntitlementOwnerRegistryPortImpl(EntitlementOwnerRegistryService service) { this.service=service; }
    @Override public OwnerRef resolve(EntitlementContext ctx,SourceIdentity source) { return service.resolve(ctx,source); }
}
