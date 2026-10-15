package ai.neargo.shop.pay.channel;

import java.util.Set;

/**
 * Optional, certified funds operations implemented alongside a native PayGateway.
 * Capabilities are local facts about the exact recipient snapshot, not remote calls or operator toggles.
 */
public interface PayFundsOperations {
    /** Only independently verified execution and result-query capabilities may be advertised. */
    Capabilities fundsCapabilities(Context context);

    /** Executes a committed immutable request outside a database transaction. */
    Receipt executeFunds(Request request);

    /** Queries only the original funds request, never the order-payment status. */
    Receipt queryFunds(Request request);

    enum Path { DIRECT, AGGREGATED }
    enum Operation { SUBSIDY, INTERNAL_REPLENISHMENT, TRANSFER, COLLECTION, EXPIRY_INCOME, COLLECTION_REVERSAL }
    enum Status { SUCCEEDED, REJECTED, UNKNOWN }
    record Context(Path path,String channel,String merchantNo,String storeNo,String accountVersion,String subMerchantId,
                   String tradeNo,String outTradeNo,long orderTotalMinor) { }
    record Capabilities(Set<Operation> executable,Set<Operation> queryable) {
        public Capabilities { executable=Set.copyOf(executable); queryable=Set.copyOf(queryable); }
        /** Every new promise needs a recoverable result query as well as execution. */
        public boolean supports(Operation operation) {
            return executable.contains(operation) && queryable.contains(operation);
        }
    }
    record Request(String ticketNo,String providerIdem,Operation operation,Context context,
                   String currency,long amountMinor,String originalTicketNo,String originalEvidenceNo) {
        public Request(String ticketNo,String providerIdem,Operation operation,Context context,
                       String currency,long amountMinor) {
            this(ticketNo,providerIdem,operation,context,currency,amountMinor,null,null);
        }
    }
    /** SUCCEEDED means completed money movement with evidence; an accepted or pending request is UNKNOWN. */
    record Receipt(String providerIdem,Status status,String currency,long amountMinor,String evidenceNo,String reason) { }
}
