package ai.neargo.shop.pay.storedvalue.issuer;

import ai.neargo.shop.spi.entitlement.EntitlementContext;
import java.time.Instant;
import java.util.Map;

/** Stable PAY business contract; original owner checks and transaction boundaries remain in its implementation. */
public interface StoredValueIssueQuotaService {
    Reservation reserve(EntitlementContext actor,String rechargeNo);

    void commit(EntitlementContext actor,String rechargeNo);

    void release(EntitlementContext actor,String rechargeNo);

    boolean expire(EntitlementContext actor,String rechargeNo,Instant now);

    void lockForOriginal(EntitlementContext actor,String rechargeNo);

    void lockForAccount(EntitlementContext actor,String accountNo);

    Map<String,Object> lockIssuerQuota(EntitlementContext actor,String issuer,String currency);

    public record Reservation(String reservationNo,String status,long amountMinor) { }
}
