package ai.neargo.shop.common.jdbc;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Owner-neutral JDBC/JSON primitives; callers retain their own SQL, authorization and row locks. */
@Repository
public class JdbcTransactionStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;

    public JdbcTransactionStore(JdbcTemplate jdbc,
                                @Qualifier("transactionManager") PlatformTransactionManager manager,
                                ObjectMapper json) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(manager);
        this.json = json;
    }

    /** Joined transactions never retry after their owner transaction has been marked for rollback. */
    public <T> T write(Supplier<T> action) {
        boolean joined = TransactionSynchronizationManager.isActualTransactionActive();
        for (int attempt = 0; ; attempt++) {
            try {
                return tx.execute(status -> action.get());
            } catch (DuplicateKeyException | CannotAcquireLockException failure) {
                if (joined || attempt >= 2) throw BizException.of(ErrorCode.CONFLICT);
            }
        }
    }

    public Map<String, Object> one(String sql, Object... args) {
        var list = rows(sql, args);
        return list.isEmpty() ? null : list.getFirst();
    }

    public List<Map<String, Object>> rows(String sql, Object... args) {
        return jdbc.queryForList(sql, args);
    }

    public int update(String sql, Object... args) {
        return jdbc.update(sql, args);
    }

    public void insert(String table, Map<String, Object> row) {
        jdbc.update("INSERT INTO " + table + " (" + String.join(",", row.keySet()) + ") VALUES ("
                + String.join(",", Collections.nCopies(row.size(), "?")) + ")", row.values().toArray());
    }

    public Map<String, Object> base(String tenant, String actor) {
        var at = Timestamp.from(Instant.now());
        var row = new LinkedHashMap<String, Object>();
        row.put("tenant_no", tenant);
        row.put("version", 0);
        row.put("deleted", 0);
        row.put("created_at", at);
        row.put("updated_at", at);
        row.put("created_by", actor);
        row.put("updated_by", actor);
        return row;
    }

    public String encode(Object value) {
        return json.writeValueAsString(value);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> decode(String value) {
        return json.readValue(value, Map.class);
    }

    public static String s(Map<String, Object> row, String key) {
        return row == null || row.get(key) == null ? null : row.get(key).toString();
    }

    public static long n(Map<String, Object> row, String key) {
        return row == null || row.get(key) == null ? 0 : ((Number) row.get(key)).longValue();
    }
}
