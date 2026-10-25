package ai.neargo.shop.report.config;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.datasource.AbstractDataSource;

/** 报表库就绪边界；隔离模式由任务调用，主业务启动不访问报表连接。 */
public final class ReportStorageInitializer {

    public enum State { NOT_READY, INITIALIZING, READY, FAILED }

    private final DataSource source;
    private final ReportStoreProperties props;
    private volatile State state = State.NOT_READY;
    private volatile String failureCode;

    public ReportStorageInitializer(DataSource source, ReportStoreProperties props) {
        this.source = source;
        this.props = props;
    }

    public State state() { return state; }

    public String failureCode() { return failureCode; }

    /** 同实例只允许一个初始化执行者；Flyway负责跨实例迁移锁，不自动repair。 */
    public synchronized void initialize() {
        if (state == State.READY) {
            return;
        }
        state = State.INITIALIZING;
        try {
            boolean strict = props.getInitializationMode()
                    == ReportStoreProperties.InitializationMode.STRICT;
            if (strict && !props.isFlywayEnabled()) {
                // 兼容原零售显式禁用迁移的合同；隔离模式不能走此捷径。
                state = State.READY;
                return;
            }
            Flyway flyway = Flyway.configure().dataSource(source)
                    .locations(props.getFlywayLocations())
                    .table(ReportDataSourceConfig.HISTORY_TABLE)
                    .baselineOnMigrate(true).baselineVersion("0").load();
            boolean migrator = strict || props.getInitializationRole()
                    == ReportStoreProperties.InitializationRole.MIGRATOR;
            if (migrator && props.isFlywayEnabled()) {
                flyway.migrate();
            }
            flyway.validate();
            if (flyway.info().pending().length != 0 || flyway.info().applied().length == 0) {
                throw new IllegalStateException("REPORT_SCHEMA_NOT_READY");
            }
            failureCode = null;
            state = State.READY;
        } catch (RuntimeException ex) {
            failureCode = ex.getClass().getSimpleName();
            state = State.FAILED;
            throw ex;
        }
    }

    /** 不暴露底层连接/unwrap，未就绪不能经事务管理器绕过JdbcClient保护。 */
    public DataSource guardedDataSource() {
        return guarded;
    }

    private final DataSource guarded = new AbstractDataSource() {
        @Override
        public Connection getConnection() throws SQLException {
            requireReady();
            return source.getConnection();
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            requireReady();
            return source.getConnection(username, password);
        }
    };

    private void requireReady() throws SQLException {
        if (state != State.READY
                && props.getInitializationMode() == ReportStoreProperties.InitializationMode.ISOLATED
                && props.getInitializationRole() == ReportStoreProperties.InitializationRole.VALIDATOR) {
            try {
                // 每个API实例自行验证；不依赖全局任务被路由到本实例，也不执行迁移。
                initialize();
            } catch (RuntimeException ex) {
                // 不将底层URL、账号或连接异常消息带到API返回值。
                throw new SQLException("REPORT_STORAGE_NOT_READY", "08001");
            }
        }
        if (state != State.READY) {
            throw new SQLException("REPORT_STORAGE_NOT_READY", "08001");
        }
    }
}
