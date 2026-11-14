package ai.neargo.shop.pay.client;

import ai.neargo.shop.spi.entitlement.EntitlementContext;
import java.time.Instant;

/** Service-authenticated consumer reads carry server-selected identity only. */
public final class PayStoredValueConsumerRequests {
    private PayStoredValueConsumerRequests() { }
    public record Store(EntitlementContext context, String storeNo) { }
    public record Records(EntitlementContext context, String storeNo, String type, Instant from, Instant to,
                          String focusDocumentNo, String cursor, int size) { }
    public record Fact(EntitlementContext context, String usageNo) { }
    public record Issuers(EntitlementContext context,String cursor,int size) { }
    public record Issuer(EntitlementContext context,String issuerRef) { }
    public record Locate(EntitlementContext context,String documentNo) { }
    public record IssuerRecords(EntitlementContext context,String issuerRef,String type,Instant from,Instant to,String focus,String cursor,int size) { }
}
