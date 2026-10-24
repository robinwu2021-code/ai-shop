package ai.neargo.shop.pay.storedvalue.issuer;

import ai.neargo.shop.spi.entitlement.EntitlementContext;





/** Stable business API; implementation keeps the original transaction and owner checks. */
public interface StoredValueIssuerAccountService {
    public record Lookup(boolean issuerScoped,String accountNo) { }

    Lookup credit(EntitlementContext ctx,String rechargeNo);

    Lookup lookup(String tenant,String entity,String store,String currency,String identity,boolean lock);
}
