package ai.neargo.shop.pay.points.query;

import java.util.List;

/** Read a coherent financial snapshot without exposing posting payloads or bank routes. */
public interface PointsOverviewService {
    Overview overview(String typeCode, String market);

    ReceivablePage receivables(String typeCode, String storeNo, long after, int limit);

    record Receivable(
            long id,
            String receivableNo,
            String storeNo,
            String state,
            long originalMinor,
            long recoveredMinor,
            long lockedMinor,
            long outstandingMinor,
            long version) {}

    record ReceivablePage(
            String typeCode,
            String currency,
            int scale,
            List<Receivable> rows,
            long next,
            boolean hasMore) {
        public ReceivablePage {
            rows = List.copyOf(rows);
        }
    }

    record Overview(
            String typeCode,
            String market,
            String currency,
            int scale,
            long ratePoints,
            long rateMinor,
            long uMinor,
            long cMinor,
            long dMinor,
            long bMinor,
            long reservedBMinor,
            long lockedUMinor,
            long lockedBMinor,
            long receivableMinor) {}
}
