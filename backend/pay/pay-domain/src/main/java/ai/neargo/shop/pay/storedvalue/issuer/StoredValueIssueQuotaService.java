package ai.neargo.shop.pay.storedvalue.issuer;

import ai.neargo.shop.spi.entitlement.EntitlementContext;
import java.time.Instant;





/** Stable business API; implementation keeps the original transaction and owner checks. */
public interface StoredValueIssueQuotaService {
    public record Reservation(String reservationNo,String status,long amountMinor) { }

    Reservation reserve(EntitlementContext actor,String rechargeNo);

    void commit(EntitlementContext actor,String rechargeNo);

    void release(EntitlementContext actor,String rechargeNo);

    boolean expire(EntitlementContext actor,String rechargeNo,Instant now);

    void lockForOriginal(EntitlementContext actor,String rechargeNo);

    void lockForAccount(EntitlementContext actor,String accountNo);
}
