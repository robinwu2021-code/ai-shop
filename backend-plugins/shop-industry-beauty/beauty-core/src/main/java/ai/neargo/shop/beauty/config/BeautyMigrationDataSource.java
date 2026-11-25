package ai.neargo.shop.beauty.config;

import org.springframework.jdbc.datasource.DelegatingDataSource;
import ai.neargo.shop.beauty.policy.ServiceExecutionSchemaVerifier;
import java.util.stream.Collectors;

import javax.sql.DataSource;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

/** Execute the frozen V10 DDL on MySQL without changing its resource bytes or Flyway checksum. */
public final class BeautyMigrationDataSource extends DelegatingDataSource {
    private static final String CREATE;
    private static final String HEADER;
    private static final String MYSQL_CREATE;
    static {
        try (var stream = BeautyMigrationDataSource.class.getClassLoader().getResourceAsStream(
                "db/industry/beauty/V10__beauty_service_execution_fence.sql")) {
            String sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            int start = sql.indexOf("CREATE TABLE bty_service_execution_fence (");
            HEADER = sql.substring(0, start);
            CREATE = sql.substring(start, sql.indexOf(';', start));
            MYSQL_CREATE = CREATE.lines().map(line -> {
                int check = line.indexOf(" CHECK (");
                if (check < 0) return line;
                int end = line.lastIndexOf(')');
                return line.substring(0, check + 8)
                        + ServiceExecutionSchemaVerifier.mysqlCheck(line.substring(check + 8, end))
                        + line.substring(end);
            }).collect(Collectors.joining("\n"));
        } catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    /** Only the private Flyway receives this datasource; application connections remain unwrapped. */
    public BeautyMigrationDataSource(DataSource delegate) { super(delegate); }

    @Override public Connection getConnection() throws SQLException { return adapt(super.getConnection()); }
    @Override public Connection getConnection(String user, String password) throws SQLException {
        return adapt(super.getConnection(user, password));
    }

    private static Connection adapt(Connection connection) throws SQLException {
        try {
            if (!"MySQL".equals(connection.getMetaData().getDatabaseProductName())) return connection;
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                        Object[] effective = args;
                        String name = method.getName();
                        if ((name.equals("prepareStatement") || name.equals("prepareCall"))
                                && args != null && args[0] instanceof String sql) {
                            effective = args.clone(); effective[0] = rewrite(sql);
                        }
                        Object result = invoke(connection, method, effective);
                        if (result instanceof Statement statement && (name.equals("createStatement")
                                || name.equals("prepareStatement") || name.equals("prepareCall"))) {
                            return statement(statement);
                        }
                        return result;
                    });
        } catch (SQLException | RuntimeException failure) {
            try { connection.close(); } catch (SQLException close) { failure.addSuppressed(close); }
            throw failure;
        }
    }

    private static Object statement(Statement statement) {
        Class<?> type = statement instanceof CallableStatement ? CallableStatement.class
                : statement instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
            Object[] effective = args;
            if (args != null && args.length > 0 && args[0] instanceof String sql
                    && (method.getName().startsWith("execute") || method.getName().equals("addBatch"))) {
                effective = args.clone(); effective[0] = rewrite(sql);
            }
            return invoke(statement, method, effective);
        });
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }

    static String rewrite(String sql) {
        String statement = sql.strip();
        if (statement.endsWith(";")) statement = statement.substring(0, statement.length() - 1);
        if (!statement.equals(CREATE) && !statement.equals(HEADER + CREATE)) return sql;
        return sql.replace(CREATE, MYSQL_CREATE);
    }
}
