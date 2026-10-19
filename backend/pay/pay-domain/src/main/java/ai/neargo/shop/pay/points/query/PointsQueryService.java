package ai.neargo.shop.pay.points.query;

import ai.neargo.shop.pay.points.store.PointsOperationsStore.Kind;

import java.util.List;
import java.util.Map;

/**
 * Internal Ops read facade; endpoint authorization and store ownership are applied by the B-stage
 * adapter.
 */
public interface PointsQueryService {
    Page page(String typeCode, Kind kind, long after, int limit);

    record Page(List<Map<String, Object>> rows, long next, boolean hasMore) {
        public Page {
            rows = List.copyOf(rows);
        }
    }
}
