package ai.neargo.shop.pay.points.store;

import java.util.List;
import java.util.Map;

/** Current reads used under the type/pool lock; statement writes remain in posting. */
public interface StatementStore {
    Map<String, Object> lock(String statementNo);
    Map<String, Object> lockSource(String positionNo);
    List<Map<String, Object>> candidates(String typeCode, String storeNo);
}
