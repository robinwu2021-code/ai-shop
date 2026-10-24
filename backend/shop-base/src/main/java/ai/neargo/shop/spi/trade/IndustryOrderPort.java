package ai.neargo.shop.spi.trade;

import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Credentials;
import java.util.List;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Scope;

/** Source-authorized formal order creation; no client price or debit authorization is accepted. */
public interface IndustryOrderPort {
    enum Mode { PREPAY, POSTPAY }
    record Identity(String tenantNo, String entityNo, String storeNo, String buyerNo, String actorNo) { }
    record Command(Identity identity, PricingSourceRef source, Credentials credentials, String quoteNo,
            String confirmationDigest, Mode mode, String payScene) { }
    record Allocation(String sourceLineNo, String componentRef, long orderItemId, int qty,
            long amountMinor, String currency, String hash) { }
    /** PREPARED has original document identities, null money/currency and no committed item allocations. */
    record Result(String orderNo, String subOrderNo, String quoteNo, String status, String currency,
            Long payAmountMinor, long sourceVersion, int inventoryLifecycleVersion, List<Allocation> allocations) {
        public Result { allocations = List.copyOf(allocations); }
    }
    Result place(Command command, String key);
    Result cancel(Command command, String key);
    Result query(Identity identity, String key);
    Result get(Identity identity, String orderNo);
    Result getForStore(Scope scope, String actorNo, String orderNo);
}
