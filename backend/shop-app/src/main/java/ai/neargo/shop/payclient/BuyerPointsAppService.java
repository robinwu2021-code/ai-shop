package ai.neargo.shop.payclient;

import java.util.List;

/** Buyer-owned projections of pay's points ledger; types remain separate across markets. */
public interface BuyerPointsAppService {
    List<Account> accounts(String userNo);

    EntryPage records(String userNo, String typeCode, long after, int limit);

    record Account(
            String typeCode,
            String name,
            String market,
            String currency,
            String status,
            long balance,
            long pendingBalance,
            long heldBalance,
            long ratePoints,
            long rateMinor,
            int deductRatioBp,
            int idleDays,
            boolean frozen,
            Long expireAt) {}

    record Entry(String entryNo, String bucket, long points, long balanceAfter, long at, String voucherNo, String voucherDocumentNo, String direction, String businessRef, String orderNo) {
        public Entry(String entryNo, String bucket, long points, long balanceAfter, long at) {
            this(entryNo, bucket, points, balanceAfter, at, null, null, null, null, null);
        }
    }

    record EntryPage(List<Entry> rows, long next, boolean hasMore) {
        public EntryPage {
            rows = List.copyOf(rows);
        }
    }
}
