package ai.neargo.shop.beauty.config;

import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.regex.Pattern;

/** Adapts only the frozen V10 DDL sent to MySQL 9. Flyway reads the original resource and checksum. */
final class BeautyMigrationDataSource extends DelegatingDataSource {
    private static final Pattern FENCE_CHECK = Pattern.compile(
            "(?m)^([ \t]*CONSTRAINT ck_bsef_json_[a-z0-9_]+ CHECK \\(COALESCE\\([^\\r\\n]+,0\\))(\\),?[ \t]*)$");

    BeautyMigrationDataSource(DataSource source) {
        super(source);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return adapt(super.getConnection());
    }

    @Override
    public Connection getConnection(String user, String password) throws SQLException {
        return adapt(super.getConnection(user, password));
    }

    private static Connection adapt(Connection connection) throws SQLException {
        try {
            var product = connection.getMetaData();
            if (!"MySQL".equals(product.getDatabaseProductName()) || product.getDatabaseMajorVersion() != 9) {
                return connection;
            }
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                        Object result = invoke(connection, method, args);
                        if (!"createStatement".equals(method.getName())) return result;
                        return Proxy.newProxyInstance(Statement.class.getClassLoader(), new Class<?>[]{Statement.class},
                                (p, call, parameters) -> {
                                    if (call.getName().startsWith("execute")
                                            && parameters != null && parameters.length > 0
                                            && parameters[0] instanceof String sql
                                            && sql.contains("CREATE TABLE bty_service_execution_fence (")) {
                                        parameters[0] = FENCE_CHECK.matcher(sql).replaceAll("$1 = 1$2");
                                    }
                                    return invoke(result, call, parameters);
                                });
                    });
        } catch (SQLException | RuntimeException failure) {
            try {
                connection.close();
            } catch (SQLException close) {
                failure.addSuppressed(close);
            }
            throw failure;
        }
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }
}
