package ai.neargo.commerce.persistence;

import org.springframework.jdbc.datasource.AbstractDataSource;

import java.lang.reflect.*;
import java.sql.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.sql.DataSource;

/** Pauses after the actual first catalogue query, while its transaction and locks remain open. */
final class PausingCatalogDataSource extends AbstractDataSource {
    private final DataSource delegate;
    final CountDownLatch typeRead = new CountDownLatch(1), resume = new CountDownLatch(1);
    private final AtomicBoolean once = new AtomicBoolean();

    PausingCatalogDataSource(DataSource delegate) {
        this.delegate = delegate;
    }

    public Connection getConnection() throws SQLException {
        return wrap(delegate.getConnection());
    }

    public Connection getConnection(String user, String password) throws SQLException {
        return wrap(delegate.getConnection(user, password));
    }

    private Connection wrap(Connection connection) {
        return (Connection)
                Proxy.newProxyInstance(
                        Connection.class.getClassLoader(),
                        new Class<?>[] {Connection.class},
                        (proxy, method, args) -> {
                            Object result = invoke(connection, method, args);
                            if (method.getName().equals("prepareStatement")
                                    && args[0] instanceof String sql
                                    && sql.startsWith("SELECT * FROM sys_business_type ")) {
                                var statement = (PreparedStatement) result;
                                return Proxy.newProxyInstance(
                                        PreparedStatement.class.getClassLoader(),
                                        new Class<?>[] {PreparedStatement.class},
                                        (p, m, a) -> {
                                            Object value = invoke(statement, m, a);
                                            if (m.getName().equals("executeQuery")
                                                    && once.compareAndSet(false, true)) {
                                                typeRead.countDown();
                                                if (!resume.await(20, TimeUnit.SECONDS))
                                                    throw new IllegalStateException(
                                                            "test reader was not resumed");
                                            }
                                            return value;
                                        });
                            }
                            return result;
                        });
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
