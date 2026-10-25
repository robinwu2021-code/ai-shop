package ai.neargo.shop.report.config;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.atomic.AtomicLong;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.springframework.jdbc.datasource.AbstractDataSource;

/** A single bounded probe per instance; report failure never authorizes a fallback database. */
public final class ReportStorageInitializer {
    public enum State { NOT_READY, INITIALIZING, READY, FAILED }
    public enum FailureKind { NONE, TEMPORARY, MISCONFIGURED, SCHEMA_NOT_READY, MIGRATION_FAILED }

    private final DataSource source;
    private final ReportStoreProperties props;
    private final ReentrantLock probe = new ReentrantLock();
    private volatile State state = State.NOT_READY;
    private volatile String failureCode;
    private volatile FailureKind failureKind = FailureKind.NONE;
    private volatile long nextProbeNanos;
    private final AtomicLong readinessGeneration=new AtomicLong();

    public ReportStorageInitializer(DataSource source, ReportStoreProperties props) {
        this.source = source; this.props = props;
    }
    public State state() { return state; }
    public String failureCode() { return failureCode; }
    public FailureKind failureKind() { return failureKind; }

    public void initialize() {
        if (state == State.READY) return;
        if (!probe.tryLock()) throw new IllegalStateException("REPORT_PROBE_IN_PROGRESS");
        boolean migrationStarted = false;
        try {
            if (state == State.READY) return;
            if (failureKind == FailureKind.MISCONFIGURED || failureKind == FailureKind.MIGRATION_FAILED)
                throw new IllegalStateException("REPORT_STORAGE_REQUIRES_OPERATOR");
            if (System.nanoTime() < nextProbeNanos) throw new IllegalStateException("REPORT_STORAGE_RETRY_LATER");
            state = State.INITIALIZING;
            boolean strict = props.getInitializationMode() == ReportStoreProperties.InitializationMode.STRICT;
            if (strict && !props.isFlywayEnabled() && !props.getFood().isEnabled()) {
                readinessGeneration.incrementAndGet(); state = State.READY; return;
            }
            new ReportDatabaseIdentityGuard().verify(source, props, props.getAccessRole());
            boolean migrator = strict || props.getInitializationRole() == ReportStoreProperties.InitializationRole.MIGRATOR;
            if (migrator && props.isFlywayEnabled()) {
                var config = props.getMigrationDatasource();
                if (config.getUrl() == null || config.getUrl().isBlank())
                    throw new IllegalStateException("REPORT_MIGRATION_DATASOURCE_REQUIRED");
                try (HikariDataSource migration = migrationDataSource(config)) {
                    new ReportDatabaseIdentityGuard().verify(migration, props, ReportStoreProperties.AccessRole.MIGRATOR);
                    migrationStarted = true;
                    flyway(migration).migrate();
                }
            }
            Flyway validator = flyway(source);
            validator.validate();
            if (validator.info().pending().length != 0 || validator.info().applied().length == 0) {
                failureKind = FailureKind.SCHEMA_NOT_READY;
                throw new IllegalStateException("REPORT_SCHEMA_NOT_READY");
            }
            if(props.getFood().isEnabled()) verifyFoodSchema();
            failureCode = null; failureKind = FailureKind.NONE; readinessGeneration.incrementAndGet();state = State.READY;
        } catch (RuntimeException ex) {
            if (state == State.INITIALIZING) {
                if (migrationStarted) failureKind = FailureKind.MIGRATION_FAILED;
                else if (ex instanceof ReportDatabaseIdentityGuard.ReportDatabaseConnectionException)
                    failureKind = FailureKind.TEMPORARY;
                else if (ex instanceof FlywayException || ex instanceof SchemaNotReady) failureKind = FailureKind.SCHEMA_NOT_READY;
                else if (failureKind != FailureKind.SCHEMA_NOT_READY) failureKind = FailureKind.MISCONFIGURED;
                state = State.FAILED;
                failureCode = failureKind.name();
                nextProbeNanos = System.nanoTime() + props.getRetryDelayMillis() * 1000000L;
            }
            throw new IllegalStateException("REPORT_STORAGE_" + failureKind.name());
        } finally { probe.unlock(); }
    }

    private static final class SchemaNotReady extends IllegalStateException {}
    private void verifyFoodSchema() {
        String[] probes={
                "SELECT tenant_no,entity_no,store_no,first_stat_date,last_planned_date,metric_version,default_for_reads,read_slot FROM rpt_scope_cursor WHERE 1=0",
                "SELECT build_no,attempt_no,fence,actor_no,request_hash,day_evidence,required_sources,validation_hash,next_retry_at FROM rpt_build_batch WHERE 1=0",
                "SELECT build_no,attempt_no,owner_code,family_code,source_evidence,checksum,completeness FROM rpt_source_manifest WHERE 1=0",
                "SELECT active_build_no,active_attempt_no,generation,current_fence,lease_until FROM rpt_day_publication WHERE 1=0",
                "SELECT dirty_generation,acked_generation,ready_after FROM rpt_dirty_day WHERE 1=0",
                "SELECT count_value,quantity_value,amount_minor,known_count_value,known_quantity_value,known_amount_minor,dimensions FROM rpt_food_metric_daily WHERE 1=0",
                "SELECT query_snapshot,query_hash,execution_ref,retired_objects,attempt_no,lease_owner,lease_until FROM rpt_export_task WHERE 1=0"};
        try(var connection=source.getConnection();var statement=connection.createStatement()) {
            for(String sql:probes) try(var rows=statement.executeQuery(sql)) { /* structure only */ }
        } catch(SQLException failure) { throw new SchemaNotReady(); }
    }
    private Flyway flyway(DataSource ds) {
        return Flyway.configure().dataSource(ds).locations(props.getFlywayLocations())
                .table(ReportDataSourceConfig.HISTORY_TABLE).baselineOnMigrate(false).load();
    }

    private static HikariDataSource migrationDataSource(ReportStoreProperties.Datasource config) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(config.getUrl()); ds.setUsername(config.getUsername()); ds.setPassword(config.getPassword());
        // Flyway may retain its metadata connection while acquiring its migration connection.
        // Single-flight remains guarded by probe; a one-connection pool deadlocks initialization.
        ds.setPoolName("rpt-migration-pool"); ds.setMaximumPoolSize(2); ds.setConnectionTimeout(3000);
        return ds;
    }

    public DataSource guardedDataSource() { return guarded; }
    private final DataSource guarded = new AbstractDataSource() {
        @Override public Connection getConnection() throws SQLException {
            requireReady(); return connect();
        }
        @Override public Connection getConnection(String username, String password) throws SQLException {
            // Per-call credentials would bypass the effective-grant proof.
            throw new SQLException("REPORT_PER_CALL_CREDENTIALS_FORBIDDEN", "08001");
        }
    };
    private Connection connect() throws SQLException {
        try {
            Connection connection=source.getConnection();long generation=readinessGeneration.get();
            return ReportConnectionGuard.observe(connection,error->invalidate(error,generation));
        }
        catch (SQLException ex) {
            state = State.FAILED; failureKind = FailureKind.TEMPORARY; failureCode = "TEMPORARY";
            nextProbeNanos = System.nanoTime() + props.getRetryDelayMillis() * 1000000L;
            throw new SQLException("REPORT_DATABASE_UNAVAILABLE", "08001");
        }
    }
    private synchronized void invalidate(SQLException failure,long generation) {
        String sqlState=failure.getSQLState();
        if(generation!=readinessGeneration.get() || state!=State.READY || sqlState==null) return;
        if(sqlState.startsWith("08")||sqlState.startsWith("42")) {
            failureKind=sqlState.startsWith("08")?FailureKind.TEMPORARY:FailureKind.SCHEMA_NOT_READY;
            failureCode=failureKind.name();nextProbeNanos=System.nanoTime()+props.getRetryDelayMillis()*1000000L;state=State.FAILED;
        }
    }
    private void requireReady() throws SQLException {
        if (state != State.READY && props.getInitializationMode() == ReportStoreProperties.InitializationMode.ISOLATED
                && props.getInitializationRole() == ReportStoreProperties.InitializationRole.VALIDATOR) {
            try { initialize(); } catch (RuntimeException ex) { throw new SQLException("REPORT_STORAGE_NOT_READY", "08001"); }
        }
        if (state != State.READY) throw new SQLException("REPORT_STORAGE_NOT_READY", "08001");
    }
}
