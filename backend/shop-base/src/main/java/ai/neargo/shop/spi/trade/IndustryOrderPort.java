package ai.neargo.shop.spi.trade;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
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
            Long payAmountMinor, long sourceVersion, int inventoryLifecycleVersion, List<Allocation> allocations,
            String orderMode, String fulfillmentStatus, boolean acceptedUnpaid) {
        public Result { allocations = List.copyOf(allocations); }
        /** Compatibility for existing PREPAY adapters; absence of proof never authorizes unpaid acceptance. */
        public Result(String orderNo,String subOrderNo,String quoteNo,String status,String currency,
                Long amount,long version,int lifecycle,List<Allocation> allocations) {
            this(orderNo,subOrderNo,quoteNo,status,currency,amount,version,lifecycle,allocations,null,null,false);
        }
    }
    /** Advances original lifecycle-3 fulfillment only after the independent inventory owner confirms unpaid acceptance. */
    Result acceptUnpaid(Identity identity, String orderNo);
    /** Trusted current-store gateway only; actor is staff, buyer remains a real customer, and no debit is granted. */
    default Result placeForStore(Command command, String key) {
        throw BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED);
    }
    /** Explicit registered-buyer staff dine-in capability; existing command fingerprints remain unchanged. */
    default Result placeForDiningRoom(Command command, String key) {
        throw BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED);
    }
    /** Replays only the persisted staff identity and original key; never creates a replacement intent. */
    default Result queryForStore(Identity identity, String key) {
        throw BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED);
    }
    Result place(Command command, String key);
    /** Cancels only an uncommitted original staff intent; never cancels accepted service debt or refunds funds. */
    default Result cancelForStore(Command command, String key, String fulfillment) {
        throw BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED);
    }
    Result cancel(Command command, String key);
    Result query(Identity identity, String key);
    Result get(Identity identity, String orderNo);
    Result getForStore(Scope scope, String actorNo, String orderNo);
}
