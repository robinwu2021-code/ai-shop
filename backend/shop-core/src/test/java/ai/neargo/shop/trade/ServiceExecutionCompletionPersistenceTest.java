package ai.neargo.shop.trade;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionBinding;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionScope;
import ai.neargo.shop.trade.mapper.ServiceExecutionMapper;
import java.util.UUID;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Exercises the real CAS SQL and transaction rollback; native CHECK/trigger races remain MySQL integration work. */
class ServiceExecutionCompletionPersistenceTest {
    private JdbcTemplate jdbc;
    private SqlSessionFactory sessions;
    private final ExecutionScope scope = new ExecutionScope("MAIN", "M1", "S1");
    private final String digest = "a".repeat(64);

    private ExecutionBinding binding() {
        return new ExecutionBinding(scope, "BEAUTY", "OP1", "O1", "SO1", "L1", 1, "WO1", 1,
                "T1", 0, "B1", 1, "E1", 1, "SUB1", "F1", 1, 1, 1, digest,
                "D1", digest, digest, digest);
    }

    @BeforeEach
    void database() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:exec_complete_"
                + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE ord_service_execution (tenant_no VARCHAR, entity_no VARCHAR, store_no VARCHAR, "
                + "execution_no VARCHAR, unit_no VARCHAR, binding_hash VARCHAR, version BIGINT, observation_epoch BIGINT, "
                + "attempt_no INT, state VARCHAR, actual_resource_command_no VARCHAR, resource_outcome_no VARCHAR, "
                + "resource_outcome_hash VARCHAR, deleted INT, reason_code VARCHAR, next_retry_at TIMESTAMP, "
                + "updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE ord_service_execution_unit (tenant_no VARCHAR, entity_no VARCHAR, store_no VARCHAR, "
                + "unit_no VARCHAR, version BIGINT, state VARCHAR, current_execution_no VARCHAR, attempt_no INT, "
                + "task_no VARCHAR, work_order_no VARCHAR, binding_no VARCHAR, binding_revision BIGINT, "
                + "source_operation_no VARCHAR, order_no VARCHAR, sub_order_no VARCHAR, source_line_no VARCHAR, "
                + "unit_ordinal INT, quantity INT, uom VARCHAR, deleted INT, updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE ord_service_execution_action (tenant_no VARCHAR, entity_no VARCHAR, store_no VARCHAR, "
                + "execution_no VARCHAR, action_no VARCHAR, action_hash VARCHAR, kind VARCHAR, deleted INT, "
                + "expected_revision BIGINT, observation_epoch BIGINT, result_revision BIGINT, result_state VARCHAR, "
                + "version BIGINT, updated_at TIMESTAMP)");
        jdbc.update("INSERT INTO ord_service_execution VALUES ('MAIN','M1','S1','E1','U1',?,5,2,1,"
                + "'STARTED','RC1','R1',?,0,NULL,NULL,NULL)", digest, digest);
        jdbc.update("INSERT INTO ord_service_execution_unit VALUES ('MAIN','M1','S1','U1',3,'RESERVED','E1',1,"
                + "'T1','WO1','B1',1,'OP1','O1','SO1','L1',1,1,'COUNT',0,NULL)");
        jdbc.update("INSERT INTO ord_service_execution_action VALUES ('MAIN','M1','S1','E1','A1',?,'FULFILL',0,"
                + "5,2,NULL,NULL,0,NULL)", digest);
        var config = new Configuration(new Environment("completion", new JdbcTransactionFactory(), source));
        config.addMapper(ServiceExecutionMapper.class);
        config.addMapper(ServiceExecutionMapper.UnitMapper.class);
        config.addMapper(ServiceExecutionMapper.ActionMapper.class);
        sessions = new SqlSessionFactoryBuilder().build(config);
    }

    @Test
    void realCountMovesOnceAndLeavesNoCurrentReservation() {
        try (var session = sessions.openSession(false)) {
            assertThat(session.getMapper(ServiceExecutionMapper.class)
                    .markFulfilled(binding(), digest, 5, 2, "A1", digest, null)).isEqualTo(1);
            assertThat(session.getMapper(ServiceExecutionMapper.UnitMapper.class)
                    .markPerformed(binding(), "U1", 3, "A1", digest)).isEqualTo(1);
            assertThat(session.getMapper(ServiceExecutionMapper.ActionMapper.class)
                    .finalizeResult(scope, "E1", "A1", digest, 0, 2, "FULFILLED", 7)).isEqualTo(1);
            session.commit();
        }
        assertThat(jdbc.queryForObject("SELECT state FROM ord_service_execution", String.class)).isEqualTo("FULFILLED");
        assertThat(jdbc.queryForObject("SELECT state FROM ord_service_execution_unit", String.class)).isEqualTo("PERFORMED");
        assertThat(jdbc.queryForObject("SELECT current_execution_no FROM ord_service_execution_unit", String.class))
                .isNull();
        try (var session = sessions.openSession(false)) {
            assertThat(session.getMapper(ServiceExecutionMapper.class)
                    .markFulfilled(binding(), digest, 5, 2, "A1", digest, null)).isZero();
            assertThat(session.getMapper(ServiceExecutionMapper.UnitMapper.class)
                    .markPerformed(binding(), "U1", 3, "A1", digest)).isZero();
            session.rollback();
        }
    }

    @Test
    void aLostUnitCasRollsBackTheExecutionChange() {
        try (var session = sessions.openSession(false)) {
            assertThat(session.getMapper(ServiceExecutionMapper.class)
                    .markFulfilled(binding(), digest, 5, 2, "A1", digest, null)).isEqualTo(1);
            assertThat(session.getMapper(ServiceExecutionMapper.UnitMapper.class)
                    .markPerformed(binding(), "U1", 2, "A1", digest)).isZero();
            session.rollback();
        }
        assertThat(jdbc.queryForObject("SELECT state FROM ord_service_execution", String.class)).isEqualTo("STARTED");
        assertThat(jdbc.queryForObject("SELECT state FROM ord_service_execution_unit", String.class)).isEqualTo("RESERVED");
    }

    @Test
    void realCompletedFactCanRetainPriorSuspensionReason() {
        jdbc.update("UPDATE ord_service_execution SET state='STARTED_SUSPENDED',reason_code='SOURCE_SUSPENDED'");
        try (var session = sessions.openSession(false)) {
            assertThat(session.getMapper(ServiceExecutionMapper.class)
                    .markFulfilled(binding(), digest, 5, 2, "A1", digest, "SOURCE_SUSPENDED")).isEqualTo(1);
            assertThat(session.getMapper(ServiceExecutionMapper.UnitMapper.class)
                    .markPerformed(binding(), "U1", 3, "A1", digest)).isEqualTo(1);
            session.commit();
        }
        assertThat(jdbc.queryForObject("SELECT reason_code FROM ord_service_execution", String.class))
                .isEqualTo("SOURCE_SUSPENDED");
    }

    @Test
    void foreignScopeAndStaleActionEpochCannotComplete() {
        jdbc.update("UPDATE ord_service_execution_action SET store_no='FOREIGN'");
        try (var session = sessions.openSession(false)) {
            assertThat(session.getMapper(ServiceExecutionMapper.class)
                    .markFulfilled(binding(), digest, 5, 2, "A1", digest, null)).isZero();
            session.rollback();
        }
        jdbc.update("UPDATE ord_service_execution_action SET store_no='S1',observation_epoch=1");
        try (var session = sessions.openSession(false)) {
            assertThat(session.getMapper(ServiceExecutionMapper.class)
                    .markFulfilled(binding(), digest, 5, 2, "A1", digest, null)).isZero();
            session.rollback();
        }
    }
}
