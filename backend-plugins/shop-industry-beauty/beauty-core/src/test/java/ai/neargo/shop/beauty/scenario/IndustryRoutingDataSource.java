package ai.neargo.shop.beauty.scenario;

import ai.neargo.shop.beauty.config.BeautyIndustryTables;

import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import javax.sql.DataSource;

/**
 * 测试里直接读写表的 SQL 按表名分到主库或行业库（TDD-美业域迁移新库 B-5a）：行业句柄连独立的库时，测试代码照旧写
 * {@code sql.update("UPDATE bty_work_order ...")}，不用逐处改成两个 JdbcTemplate。
 *
 * <p>一条 SQL 同时用到两个库的表就报错：那正是分库后会断的写法，测试里也不许有。{@code information_schema} 的查询
 * 走主库，除非里面点名了行业表（按字面量里的表名判断）。只用于测试的自动提交读写，不支持跨语句的事务。
 */
public final class IndustryRoutingDataSource implements DataSource {
    private static final Pattern TABLE = Pattern.compile("\\b((?:bty|ind)_[a-z0-9_]+)\\b");
    private final DataSource primary;
    private final DataSource industry;

    public IndustryRoutingDataSource(DataSource primary, DataSource industry) {
        this.primary = primary;
        this.industry = industry;
    }

    /** 测试建的触发器在哪个库：{@code DROP TRIGGER 名字} 里没有表名，按建的时候记下的走。 */
    private static final java.util.Map<String, Boolean> TRIGGERS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Pattern CREATE_TRIGGER =
            Pattern.compile("(?is)^\\s*create\\s+trigger\\s+(\\w+)\\s.*?\\bon\\s+`?(\\w+)`?.*");
    private static final Pattern DROP_TRIGGER =
            Pattern.compile("(?is)^\\s*drop\\s+trigger\\s+(?:if\\s+exists\\s+)?`?(\\w+)`?.*");

    /** 这条 SQL 该去行业库吗；两个库的表混用就抛错。 */
    static boolean industry(String sql) {
        var drop = DROP_TRIGGER.matcher(sql);
        if (drop.matches()) return TRIGGERS.getOrDefault(drop.group(1).toLowerCase(java.util.Locale.ROOT), false);
        var create = CREATE_TRIGGER.matcher(sql);
        if (create.matches()) {
            boolean target = BeautyIndustryTables.NAMES.contains(create.group(2).toLowerCase(java.util.Locale.ROOT));
            TRIGGERS.put(create.group(1).toLowerCase(java.util.Locale.ROOT), target);
            return target;
        }
        Set<String> industryTables = new TreeSet<>();
        Set<String> primaryTables = new TreeSet<>();
        var matcher = TABLE.matcher(sql.toLowerCase(java.util.Locale.ROOT));
        while (matcher.find()) {
            String table = matcher.group(1);
            if (table.equals("bty_flyway_history")) primaryTables.add(table);
            else if (BeautyIndustryTables.NAMES.contains(table)) industryTables.add(table);
            else if (table.startsWith("bty_")) primaryTables.add(table);
        }
        if (!industryTables.isEmpty() && !primaryTables.isEmpty())
            throw new IllegalStateException("Test SQL mixes industry " + industryTables + " and primary "
                    + primaryTables + " tables: " + sql);
        return !industryTables.isEmpty();
    }

    @Override
    public Connection getConnection() throws SQLException {
        return connection(new Connection[2]);
    }

    @Override
    public Connection getConnection(String user, String password) throws SQLException {
        return getConnection();
    }

    private Connection connection(Connection[] opened) {
        InvocationHandler handler = (proxy, method, args) -> {
            String name = method.getName();
            if ((name.startsWith("prepare") || name.equals("nativeSQL")) && args != null && args[0] instanceof String sql)
                return invoke(target(opened, industry(sql)), method, args);
            if (name.equals("createStatement")) return statement(opened, method, args);
            if (name.equals("close") || name.equals("commit") || name.equals("rollback")
                    || name.equals("setAutoCommit") || name.equals("setReadOnly")
                    || name.equals("setTransactionIsolation")) {
                Object result = null;
                for (int i = 0; i < 2; i++) {
                    if (opened[i] == null && !name.equals("close")) target(opened, i == 1);
                    if (opened[i] != null) result = invoke(opened[i], method, args);
                }
                return result;
            }
            if (name.equals("isClosed")) return opened[0] != null && opened[0].isClosed();
            if (name.equals("unwrap") || name.equals("isWrapperFor")) return invoke(target(opened, false), method, args);
            return invoke(target(opened, false), method, args);
        };
        return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Connection.class},
                handler);
    }

    private Connection target(Connection[] opened, boolean toIndustry) throws SQLException {
        int index = toIndustry ? 1 : 0;
        if (opened[index] == null) opened[index] = (toIndustry ? industry : primary).getConnection();
        return opened[index];
    }

    /** {@code createStatement()} 的语句在执行时才知道 SQL：按第一条 SQL 选库。 */
    private Statement statement(Connection[] opened, Method create, Object[] createArgs) {
        Statement[] chosen = new Statement[1];
        InvocationHandler handler = (proxy, method, args) -> {
            if (chosen[0] == null) {
                boolean toIndustry = args != null && args.length > 0 && args[0] instanceof String sql && industry(sql);
                chosen[0] = (Statement) invoke(target(opened, toIndustry), create, createArgs);
            }
            return invoke(chosen[0], method, args);
        };
        return (Statement) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Statement.class},
                handler);
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException ex) {
            throw ex.getCause();
        }
    }

    @Override public PrintWriter getLogWriter() throws SQLException { return primary.getLogWriter(); }
    @Override public void setLogWriter(PrintWriter out) throws SQLException { primary.setLogWriter(out); }
    @Override public void setLoginTimeout(int seconds) throws SQLException { primary.setLoginTimeout(seconds); }
    @Override public int getLoginTimeout() throws SQLException { return primary.getLoginTimeout(); }
    @Override public Logger getParentLogger() { return Logger.getGlobal(); }
    @Override public <T> T unwrap(Class<T> type) throws SQLException { return primary.unwrap(type); }
    @Override public boolean isWrapperFor(Class<?> type) throws SQLException { return primary.isWrapperFor(type); }
}
