package ai.neargo.shop.pay.storedvalue.issuer;

import ai.neargo.shop.spi.entitlement.EntitlementContext;

/** Stable PAY business contract; original owner checks and transaction boundaries remain in its implementation. */
public interface StoredValueBrandLedgerService {
    boolean original(EntitlementContext ctx,String entity,String no);

    void credited(EntitlementContext ctx,String entity,String rechargeNo,String accountNo,String lotNo,String voucherNo);

    void consumed(EntitlementContext ctx,String entity,String accountNo,String voucherNo);

    public record Consumption(String voucherNo,long amountMinor) { }
}
