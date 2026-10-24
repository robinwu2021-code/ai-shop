package ai.neargo.shop.pay.storedvalue.issuer;

import java.util.*;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Repository
public class IssuerRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ObjectMapper json;

    public IssuerRepository(@Qualifier("payTxManager") PlatformTransactionManager manager, ObjectMapper json) {
        if (!(manager instanceof DataSourceTransactionManager data))
            throw new IllegalStateException("Issuer transactions require the Pay JDBC datasource");
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(data.getDataSource()));
        this.transaction = new TransactionTemplate(manager);
        this.json = json;
    }

    public Map<String,Object> one(String sql, Object... args) {
        var rows = jdbc.queryForList(sql, args);
        if (rows.size() > 1) throw new IllegalStateException("Issuer scope is not unique");
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public boolean payTransactionActive() {
        return TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.hasResource(Objects.requireNonNull(jdbc.getDataSource()));
    }

    public List<Map<String,Object>> rows(String sql, Object... args) { return jdbc.queryForList(sql, args); }

    public int update(String sql, Object... args) { return jdbc.update(sql, args); }

    public void insert(String table, Map<String,Object> values) {
        if (!Set.of("sv_issuer", "sv_issuer_version", "sv_issuer_store_access", "sv_release", "sv_release_tier", "sv_issue_quota", "sv_issue_quota_reservation", "sv_customer_issuer_index", "sv_issue_policy", "sv_issue_policy_version", "sv_recharge_plan", "sv_recharge_plan_version").contains(table))
            throw new IllegalArgumentException("Issuer table required");
        String columns = String.join(",", values.keySet());
        jdbc.update("INSERT INTO " + table + " (" + columns + ") VALUES ("
                + String.join(",", Collections.nCopies(values.size(), "?")) + ")", values.values().toArray());
    }

    public <T> T write(Supplier<T> action) {
        boolean joining = TransactionSynchronizationManager.isActualTransactionActive();
        for (int attempt = 0; ; attempt++) {
            try { return transaction.execute(status -> action.get()); }
            catch (DuplicateKeyException | CannotAcquireLockException conflict) {
                if (joining || attempt >= 2) throw conflict;
            }
        }
    }

    public <T> T decode(String value, Class<T> type) { return json.readValue(value, type); }

    public String encode(Object value) { return json.writeValueAsString(value); }
    public List<ai.neargo.shop.spi.storedvalue.StoredValueIssuers.Route> routes(String value) {
        return List.of(json.readValue(value, ai.neargo.shop.spi.storedvalue.StoredValueIssuers.Route[].class));
    }
    public static String text(Map<String,Object> row, String key) {
        return row == null || row.get(key) == null ? null : row.get(key).toString();
    }
    public static long number(Map<String,Object> row, String key) { return ((Number)row.get(key)).longValue(); }
}
