package ai.neargo.shop.pay.points.store;

import java.util.List;
import java.util.Map;

/** Current reads used under the type/pool lock; statement writes remain in posting. */
public interface StatementStore {
    Map<String, Object> lock(String statementNo);

    Map<String, Object> lockSource(String positionNo);
    Map<String,Object> firstSource(String statementNo);

    List<Map<String, Object>> forPeriod(String typeCode, String storeNo, long fromMillis);

    List<Map<String, Object>> candidates(String typeCode, String storeNo, long toMillis);

    List<Map<String, Object>> debts(String typeCode, String storeNo);
}
