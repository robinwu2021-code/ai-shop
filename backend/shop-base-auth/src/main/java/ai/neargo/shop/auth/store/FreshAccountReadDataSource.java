package ai.neargo.shop.auth.store;

import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.*;
import java.util.Objects;
import java.util.function.Supplier;

import javax.sql.DataSource;

/**
 * One primary JDBC/MyBatis resource key, two bounded physical pools. Only the verifier's scoped
 * fresh transaction routes to the reserved pool; ordinary and suspended business work never do.
 */
public final class FreshAccountReadDataSource extends AbstractDataSource implements AutoCloseable {
    private final DataSource primary;
    private final DataSource fresh;
    private final ThreadLocal<Scope> scope = new ThreadLocal<>();

    private static final class Scope {
        Object resource;
    }

    public FreshAccountReadDataSource(DataSource primary, DataSource fresh) {
        this.primary = Objects.requireNonNull(primary);
        this.fresh = Objects.requireNonNull(fresh);
        if (primary == fresh)
            throw new IllegalArgumentException("independent fresh capacity required");
    }

    private DataSource selected() {
        return scope.get() == null ? primary : fresh;
    }

    public Connection getConnection() throws SQLException {
        return selected().getConnection();
    }

    public Connection getConnection(String user, String password) throws SQLException {
        return selected().getConnection(user, password);
    }

    public <T> T read(TransactionTemplate transaction, Supplier<T> action) {
        Scope active = scope.get();
        if (active != null) {
            if (active.resource == null
                    || active.resource != TransactionSynchronizationManager.getResource(this)
                    || !TransactionSynchronizationManager.isActualTransactionActive())
                throw new FreshAccountReadUnavailable();
            return action.get();
        }
        var next = new Scope();
        scope.set(next);
        try {
            return transaction.execute(
                    status -> {
                        next.resource = TransactionSynchronizationManager.getResource(this);
                        if (next.resource == null) throw new FreshAccountReadUnavailable();
                        return action.get();
                    });
        } catch (org.springframework.transaction.TransactionException
                | org.springframework.dao.DataAccessException unavailable) {
            // No stale-cache fallback and no driver/connection credentials in the public failure.
            throw new FreshAccountReadUnavailable();
        } finally {
            scope.remove();
        }
    }

    public void close() throws Exception {
        try {
            if (fresh instanceof AutoCloseable closeable) closeable.close();
        } finally {
            if (primary instanceof AutoCloseable closeable) closeable.close();
        }
    }
}
