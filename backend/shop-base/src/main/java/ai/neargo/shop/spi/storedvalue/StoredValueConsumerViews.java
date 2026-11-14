package ai.neargo.shop.spi.storedvalue;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Consumer projections deliberately exclude account identities and balance pools. */
public final class StoredValueConsumerViews {
    private StoredValueConsumerViews() { }
    public record Store(String storeNo, String entityNo, String storeName, String storeStatus,
                        String lastActivityType, Instant lastActivityAt, Instant lastOccurredAt, String lastType) { }
    public record StoreHeader(String storeNo, String storeName, String businessTimeZone) { }
    public record Filter(String type, LocalDate startDate, LocalDate endDate) { }
    public record Record(String type, String title, String currency, long signedAmountMinor,
                         Instant occurredAt, String documentNo,String storeNo,String storeName) {
        public Record(String type,String title,String currency,long signedAmountMinor,Instant occurredAt,String documentNo){this(type,title,currency,signedAmountMinor,occurredAt,documentNo,null,null);}
    }
    public record Records(StoreHeader store, Filter filter, List<Record> records, String nextCursor,
                          boolean hasMore) { }
    public record Issuer(String issuerRef,String scopeType,String name,String entityNo,String storeNo,String currency,
                         String businessTimeZone,String lastType,Instant lastOccurredAt) { }
    public record IssuerRecords(Issuer issuer,Filter filter,List<Record> records,String nextCursor,boolean hasMore) { }
    /** Internal service-authenticated fact; never returned directly by the consumer controller. */
    public record NotificationFact(String usageNo, String userNo, String storeNo, String storeName,
                                   String title, String currency, long amountMinor, Instant occurredAt,
                                   String documentNo) { }
}
