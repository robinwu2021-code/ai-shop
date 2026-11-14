package ai.neargo.shop.config;

import org.springframework.jdbc.datasource.DelegatingDataSource;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.regex.Pattern;

import javax.sql.DataSource;

/** Flyway-only wire adaptation keeps versioned resource bytes/checksums intact on both engines. */
public final class FlywayMariaDbDialectDataSource extends DelegatingDataSource {
    private static final Pattern DROP_CHECK =
            Pattern.compile(
                    "(?im)^(\\s*ALTER\\s+TABLE\\s+[`a-zA-Z0-9_.]+\\s+DROP\\s+)CHECK(\\s+[`a-zA-Z0-9_]+\\s*;?\\s*)$");
    // Exact V550 statement only. Dynamic DDL is data here; never rewrite arbitrary SQL literals.
    private static final String V550_DYNAMIC_DROP =
            "SET @sv_release_sql = CONCAT('ALTER TABLE sv_release DROP CHECK"
                + " `',REPLACE(@sv_release_check,'`','``'),'`')";

    public FlywayMariaDbDialectDataSource(DataSource delegate) {
        super(delegate);
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
            var metadata = connection.getMetaData();
            String engine =
                    (metadata.getDatabaseProductName() + " " + metadata.getDatabaseProductVersion())
                            .toLowerCase(Locale.ROOT);
            if (!engine.contains("mariadb")) return connection;
            return (Connection)
                    Proxy.newProxyInstance(
                            Connection.class.getClassLoader(),
                            new Class<?>[] {Connection.class},
                            (proxy, method, args) -> {
                                Object[] effective = args;
                                if ((method.getName().equals("prepareStatement")
                                                || method.getName().equals("prepareCall"))
                                        && args != null
                                        && args.length > 0
                                        && args[0] instanceof String sql) {
                                    effective = args.clone();
                                    effective[0] = rewrite(sql);
                                }
                                Object result = invoke(connection, method, effective);
                                if (result instanceof Statement statement
                                        && (method.getName().equals("createStatement")
                                                || method.getName().equals("prepareStatement")
                                                || method.getName().equals("prepareCall")))
                                    return statement(statement);
                                return result;
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

    private static Object statement(Statement statement) {
        Class<?> type =
                statement instanceof CallableStatement
                        ? CallableStatement.class
                        : statement instanceof PreparedStatement
                                ? PreparedStatement.class
                                : Statement.class;
        return Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[] {type},
                (proxy, method, args) -> {
                    Object[] effective = args;
                    if (args != null
                            && args.length > 0
                            && args[0] instanceof String sql
                            && (method.getName().startsWith("execute")
                                    || method.getName().equals("addBatch"))) {
                        effective = args.clone();
                        effective[0] = rewrite(sql);
                    }
                    return invoke(statement, method, effective);
                });
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    private static String rewrite(String sql) {
        String statement = sql.strip();
        if (statement.equals(V550_DYNAMIC_DROP) || statement.equals(V550_DYNAMIC_DROP + ";")) {
            int token = sql.indexOf("DROP CHECK") + "DROP ".length();
            return sql.substring(0, token) + "CONSTRAINT" + sql.substring(token + "CHECK".length());
        }
        return DROP_CHECK.matcher(sql).replaceAll("$1CONSTRAINT$2");
    }
}
