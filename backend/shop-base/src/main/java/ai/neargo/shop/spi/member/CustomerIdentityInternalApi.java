package ai.neargo.shop.spi.member;

import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.security.DelegatedIdentity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/** Read-only actor-bound customer identification, never payment or debit permission. */
public interface CustomerIdentityInternalApi {
    String PATH = "/internal/customer-identity/resolve-reference";
    @PostExchange(PATH) Resolution resolve(@RequestBody Request request);
    record Request(DelegatedIdentity identity, EntitlementContext context, CustomerIdentityPort.ReferenceQuery
            query) { }
    record Resolution(DelegatedIdentity identity, EntitlementContext context, CustomerIdentityPort.ReferenceQuery query,
            CustomerIdentityPort.ResolvedCustomer customer) { }
}
