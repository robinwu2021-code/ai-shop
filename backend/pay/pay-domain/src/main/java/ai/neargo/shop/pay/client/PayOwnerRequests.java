package ai.neargo.shop.pay.client;

import ai.neargo.shop.spi.entitlement.ContractChecks;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.SourceIdentity;

/** Only exact source identity is accepted; no prefix, probing order or fallback flag crosses the boundary. */
public final class PayOwnerRequests {
    private PayOwnerRequests() { }
    public record Resolve(EntitlementContext context,SourceIdentity source) {
        public Resolve { ContractChecks.present(context); ContractChecks.present(source); }
    }
}
