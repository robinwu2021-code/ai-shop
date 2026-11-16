package ai.neargo.shop.pay.service;

import java.util.List;
import java.util.function.Consumer;

/** Durable pay-owned facts only. Host callbacks resolve source ownership before exposing or summing facts. */
public interface PlatformCashReporting {
    record Query(String tenantNo,long fromInclusive,long toExclusive,String currency) {
        public Query {
            if (!"MAIN".equals(tenantNo) || fromInclusive<0 || toExclusive<=fromInclusive
                    || toExclusive-fromInclusive>366L*86400000
                    || currency!=null && !currency.matches("[A-Z]{3}"))
                throw new IllegalArgumentException("PLATFORM_CASH_QUERY_INVALID");
        }
    }
    /** No provider, bank, evidence or payer identifiers are exposed. Invalid candidates must not be summed. */
    record Fact(String paymentNo,String orderNo,String originalPaymentNo,String direction,String currency,
                long amountMinor,long occurredAt,String classification,boolean verified,String diagnostic) {}
    /** One read-only repeatable-read transaction spans all batches and callbacks; no cross-request snapshot. */
    void scan(Query query,Consumer<List<Fact>> consumer);
}
