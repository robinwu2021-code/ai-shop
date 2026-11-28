package ai.neargo.shop.trade.order.refund.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.shop.spi.marketing.CouponPort;
import ai.neargo.shop.trade.order.entity.OrdRefundExecution;
import ai.neargo.shop.trade.order.mapper.RefundObligationMapper;
import ai.neargo.shop.trade.order.refund.service.RefundExecutionStore;
import ai.neargo.shop.trade.order.refund.service.impl.RefundCouponScopeServiceImpl;
import ai.neargo.shop.trade.order.refund.service.impl.RefundCouponScopeServiceImpl.Scope;
import ai.neargo.shop.trade.order.refund.service.impl.RefundObligationServiceImpl;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

class RefundObligationStorageTest {
    private static final String HASH = "a".repeat(64);

    @Test
    void canonicalSkippedObligationsAreTerminalAndCannotBeClaimed() throws Exception {
        var fixture = new Fixture(true);
        fixture.insert("RF-AS1", "COUPON", "SKIPPED");
        fixture.insert("PRF-AS2", "STOCK", "SKIPPED");
        fixture.insert("PRF-AS3", "STOCK", "UNKNOWN");

        assertThat(fixture.service.settled("RF-AS1")).isTrue();
        assertThat(fixture.mapper.pendingStock(1L, 10)).containsExactly("AS3");
        assertThat(fixture.claim("PRF-AS2")).isZero();
        assertThat(fixture.claim("PRF-AS3")).isEqualTo(1);
    }

    @Test
    void legacyNotRequiredObligationsRemainTerminalAndCannotBeClaimed() throws Exception {
        var fixture = new Fixture(false);
        fixture.insert("RF-AS1", "COUPON", "NOT_REQUIRED");
        fixture.insert("PRF-AS2", "STOCK", "NOT_REQUIRED");

        assertThat(fixture.service.settled("RF-AS1")).isTrue();
        assertThat(fixture.mapper.pendingStock(1L, 10)).isEmpty();
        assertThat(fixture.claim("PRF-AS2")).isZero();
    }

    @Test
    void skippedCouponPreparationStoresCanonicalIdentityAndKeepsLegacyState() throws Exception {
        var canonical = new Fixture(true);
        canonical.prepareSkippedCoupon();
        var row = canonical.jdbc.queryForMap("SELECT * FROM ord_refund_obligation");
        assertThat(row)
                .containsEntry("state", "SKIPPED")
                .containsEntry("after_sale_no", "AS1")
                .containsEntry("sub_order_no", "SUB1")
                .containsEntry("authority_proof", "POLICY:RF-AS1");

        var legacy = new Fixture(false);
        legacy.prepareSkippedCoupon();
        assertThat(legacy.jdbc.queryForMap("SELECT * FROM ord_refund_obligation"))
                .containsEntry("state", "NOT_REQUIRED")
                .containsEntry("after_sale_no", null)
                .containsEntry("sub_order_no", null)
                .containsEntry("authority_proof", "POLICY:RF-AS1");
    }

    private static class Fixture {
        final JdbcTemplate jdbc;
        final RefundObligationMapper mapper;
        final RefundObligationServiceImpl service;
        final RefundExecutionStore executions = mock(RefundExecutionStore.class);
        final ObjectMapper json = new ObjectMapper();
        final TransactionTemplate tx;

        Fixture(boolean canonicalStorage) throws Exception {
            var dataSource = new DriverManagerDataSource(
                    "jdbc:h2:mem:refund-obligation-" + UUID.randomUUID()
                            + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                    "sa",
                    "");
            jdbc = new JdbcTemplate(dataSource);
            jdbc.execute("""
                    CREATE TABLE ord_refund_obligation (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        execution_no VARCHAR(32) NOT NULL,
                        after_sale_no VARCHAR(32),
                        sub_order_no VARCHAR(32),
                        kind VARCHAR(24) NOT NULL,
                        source_no VARCHAR(32) NOT NULL,
                        input_hash CHAR(64) NOT NULL,
                        payload_json MEDIUMTEXT NOT NULL,
                        state VARCHAR(%d) NOT NULL,
                        authority_proof VARCHAR(128),
                        error_code VARCHAR(128),
                        next_attempt_at BIGINT NOT NULL DEFAULT 0,
                        updated_at DATETIME(3),
                        UNIQUE (execution_no, kind, source_no)
                    )
                    """.formatted(canonicalStorage ? 10 : 24));
            jdbc.execute("CREATE TABLE ord_order (id BIGINT PRIMARY KEY, order_no VARCHAR(32),"
                    + " deleted TINYINT NOT NULL DEFAULT 0)");
            jdbc.execute("CREATE TABLE ord_refund_coupon_scope (order_no VARCHAR(32) PRIMARY KEY,"
                    + " coupon_no VARCHAR(32), payload_json MEDIUMTEXT, input_hash CHAR(64))");

            var configuration = new MybatisConfiguration();
            configuration.addMapper(RefundObligationMapper.class);
            var factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setConfiguration(configuration);
            mapper = new SqlSessionTemplate(factory.getObject()).getMapper(RefundObligationMapper.class);
            var manager = new DataSourceTransactionManager(dataSource);
            tx = new TransactionTemplate(manager);
            service = new RefundObligationServiceImpl(mapper, executions, mock(CouponPort.class),
                    json, manager, canonicalStorage);
        }

        void prepareSkippedCoupon() {
            jdbc.update("INSERT INTO ord_order (id, order_no) VALUES (1, 'ORDER1')");
            String payload = json.writeValueAsString(new Scope("ORDER1", null, List.of()));
            mapper.freezeScope("ORDER1", null, payload, RefundCouponScopeServiceImpl.hash(payload));
            var execution = new OrdRefundExecution();
            execution.setOrderNo("ORDER1");
            execution.setAfterSaleNo("AS1");
            execution.setSubOrderNo("SUB1");
            execution.setClosureVersion(2);
            execution.setStatus(OrdRefundExecution.SUCCEEDED);
            when(executions.read("RF-AS1")).thenReturn(execution);
            tx.executeWithoutResult(status -> service.prepare("RF-AS1"));
            tx.executeWithoutResult(status -> service.prepare("RF-AS1"));
        }

        void insert(String no, String kind, String state) {
            jdbc.update("""
                    INSERT INTO ord_refund_obligation
                        (execution_no, kind, source_no, input_hash, payload_json, state)
                    VALUES (?, ?, 'SOURCE', ?, '{}', ?)
                    """, no, kind, HASH, state);
        }

        int claim(String no) {
            return mapper.update(
                    jdbc.queryForObject("SELECT id FROM ord_refund_obligation WHERE execution_no=?",
                            Long.class, no),
                    HASH, "PROCESSING", null, null, 100L);
        }
    }
}
