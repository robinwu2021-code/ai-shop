package ai.neargo.shop.pay.storedvalue.issuer;

import ai.neargo.shop.spi.entitlement.EntitlementContext;

/** Stable PAY business contract; original owner checks and transaction boundaries remain in its implementation. */
public interface StoredValueIssuerAccountService {
    Lookup credit(EntitlementContext ctx,String rechargeNo);

    Lookup lookup(String tenant,String entity,String store,String currency,String identity,boolean lock);

    public record Lookup(boolean issuerScoped,String accountNo) { }
}
