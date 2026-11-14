package ai.neargo.shop.report.config;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.function.Consumer;

/** Observes runtime JDBC failures without changing the guarded datasource identity used by Spring transactions. */
final class ReportConnectionGuard {
    private final Connection physical;
    private final Consumer<SQLException> failures;
    private final Connection exposed;
    private ReportConnectionGuard(Connection physical,Consumer<SQLException> failures) {
        this.physical=physical;this.failures=failures;this.exposed=wrap(Connection.class,physical);
    }
    static Connection observe(Connection connection,Consumer<SQLException> failures) {
        return new ReportConnectionGuard(connection,failures).exposed;
    }
    private <T> T wrap(Class<T> type,Object target) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(proxy,method,args)-> {
            if(method.getDeclaringClass()==Object.class) return switch(method.getName()) {
                case "equals"->proxy==args[0];case "hashCode"->System.identityHashCode(proxy);default->"ReportGuarded"+type.getSimpleName();
            };
            try {
                Object result=method.invoke(target,args);
                if(result==physical) return exposed;
                if(result instanceof CallableStatement statement) return wrap(CallableStatement.class,statement);
                if(result instanceof PreparedStatement statement) return wrap(PreparedStatement.class,statement);
                if(result instanceof Statement statement) return wrap(Statement.class,statement);
                if(result instanceof ResultSet rows) return wrap(ResultSet.class,rows);
                return result;
            } catch (InvocationTargetException invocation) {
                Throwable cause=invocation.getCause();
                if(cause instanceof SQLException sql) {
                    failures.accept(sql);
                    String state=sql.getSQLState();
                    if(state!=null && (state.startsWith("08")||state.startsWith("42")))
                        throw new SQLException(state.startsWith("08")?"REPORT_DATABASE_UNAVAILABLE":"REPORT_SCHEMA_NOT_READY",state,sql.getErrorCode());
                }
                throw cause;
            }
        }));
    }
}
