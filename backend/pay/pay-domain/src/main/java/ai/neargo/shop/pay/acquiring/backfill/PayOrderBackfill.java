package ai.neargo.shop.pay.acquiring.backfill;

import java.util.List;

/** B6/B7 links verified historical cash without re-executing payments, refunds or approvals. */
public interface PayOrderBackfill {
    /** Source owner supplies identity and any verified historical display names.
     * fundsPath is an optional assertion, never historical evidence. */
    Long backfill(String orderNo, String bizType, Long storeId, String storeName, String entityName, String market,
                  String fundsPath);

    /** Read-only assessment. Apply rechecks the same facts under locks; a preview is not a durable authorization. */
    Assessment inspect(String orderNo, String bizType, Long storeId, String storeName, String entityName, String market,
                       String fundsPath);

    /** Compatibility scan starts at the beginning; operators can use a cursor to pass unresolved cases. */
    default List<String> pendingOrderNos(int limit) {
        return pendingOrderNos("", limit);
    }

    /** Bounded keyset scan includes orphan refunds; blocked records stay discoverable on a later full scan. */
    List<String> pendingOrderNos(String afterOrderNo, int limit);

    enum Status { READY, LINKS_ONLY, ALREADY_LINKED, BLOCKED }

    /** Original frozen source identifiers let the bridge compare them with the business order's owner. */
    record Assessment(Status status, String reason, Long payOrderId, String fundsPath, String currency,
                      long amountMinor, long paidMinor, long refundedMinor, String payChannel,
                      List<String> storeNos, List<String> merchantNos, String userNo, String outTradeNo) {
        public Assessment {
            storeNos = List.copyOf(storeNos);
            merchantNos = List.copyOf(merchantNos);
        }
    }
}
