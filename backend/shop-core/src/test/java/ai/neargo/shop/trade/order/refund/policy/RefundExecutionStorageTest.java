package ai.neargo.shop.trade.order.refund.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.trade.order.entity.OrdRefundExecution;
import ai.neargo.shop.trade.order.mapper.CanonicalRefundExecutionMapper;
import ai.neargo.shop.trade.order.mapper.CanonicalRefundExecutionMapper.CashRow;
import ai.neargo.shop.trade.order.mapper.RefundExecutionMapper;
import ai.neargo.shop.trade.order.refund.model.RefundLine;
import ai.neargo.shop.trade.order.refund.model.RefundPlan;
import ai.neargo.shop.trade.order.refund.service.CanonicalRefundExecutionJournal;
import ai.neargo.shop.trade.order.refund.service.RefundExecutionStore;
import ai.neargo.shop.trade.port.RefundQuantityFactsPortImpl;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.ObjectMapper;

class RefundExecutionStorageTest {
    @Test
    void canonicalCashJournalFreezesItsPlanAndRetainsOnlyDueRecovery() throws Exception {
        String nativeUrl = System.getenv("REFUND_EXECUTION_TEST_JDBC_URL");
        if (nativeUrl != null) {
            assertThat(nativeUrl).matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/refund_exec_test_[0-9a-f]+");
        }
        var dataSource = nativeUrl == null
                ? new DriverManagerDataSource("jdbc:h2:mem:refund-execution-" + UUID.randomUUID()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "")
                : new DriverManagerDataSource(nativeUrl, "root", "");
        var jdbc = new JdbcTemplate(dataSource);
        if (nativeUrl == null) {
            createH2Table(jdbc);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ord_refund_obligation", Long.class)).isZero();
        var configuration = new MybatisConfiguration();
        configuration.addMapper(CanonicalRefundExecutionMapper.class);
        configuration.addMapper(RefundExecutionMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        var session = new SqlSessionTemplate(factory.getObject());
        var json = new ObjectMapper();
        var canonical = session.getMapper(CanonicalRefundExecutionMapper.class);
        var journal = new CanonicalRefundExecutionJournal(canonical, json);
        var store = new RefundExecutionStore(session.getMapper(RefundExecutionMapper.class), journal, true);
        var quantity = new RefundQuantityFactsPortImpl(
                new DataSourceTransactionManager(dataSource), json, journal, true);

        var original = new OrdRefundExecution();
        original.setExecutionNo("RF-AS1");
        original.setAfterSaleNo("AS1");
        original.setSubOrderNo("SUB1");
        original.setOrderNo("ORDER1");
        original.setTenantNo("MAIN");
        original.setOriginPaymentNo("ORIGINAL-PAYMENT");
        original.setCurrency("TWD");
        original.setAmountMinor(123L);
        String frozenPlan = json.writeValueAsString(new RefundPlan("SUB1",
                List.of(new RefundLine(17L, 2, 123L)), 0));
        original.setPlanJson(frozenPlan);
        original.setStockNeedsJson("[]");
        original.setStatus(OrdRefundExecution.PREPARED);
        original.setPhase(OrdRefundExecution.REVERSAL);
        original.setResourceStatus(OrdRefundExecution.PENDING);
        original.setClosureStatus("LEGACY");
        original.setNextAttemptAt(0L);
        store.insert(original);
        assertThatThrownBy(() -> store.insert(original)).isInstanceOf(DuplicateKeyException.class);

        assertThat(jdbc.queryForMap("SELECT kind, source_no, state FROM ord_refund_obligation"))
                .containsEntry("kind", "CASH")
                .containsEntry("source_no", "ORIGINAL-PAYMENT")
                .containsEntry("state", "PENDING");
        assertThat(store.due(0, 5)).hasSize(1);
        var first = canonical.find("RF-AS1");
        var current = store.byAfterSale("AS1");
        var stale = store.read("RF-AS1");
        current.setStatus(OrdRefundExecution.UNKNOWN);
        current.setPhase(OrdRefundExecution.CASH);
        current.setPaymentNo("REFUND-PAYMENT");
        current.setNextAttemptAt(100L);
        store.save(current);
        assertThat(store.due(99, 5)).isEmpty();
        assertThat(store.due(100, 5)).singleElement().satisfies(row -> {
            assertThat(row.getPaymentNo()).isEqualTo("REFUND-PAYMENT");
            assertThat(row.getStatus()).isEqualTo(OrdRefundExecution.UNKNOWN);
            assertThat(row.getPlanJson()).isEqualTo(frozenPlan);
        });
        var staleResult = (String) first.get("result_json");
        assertThat(canonical.update(current.getId(), staleResult, new CashRow("RF-AS1", "AS1", "SUB1",
                "ORIGINAL-PAYMENT", "TWD", 123L, (String) first.get("payload_json"),
                (String) first.get("input_hash"), "PENDING", staleResult, null, null, 0L, null))).isZero();
        assertThatThrownBy(() -> store.save(stale)).isInstanceOf(BizException.class);
        var alteredPlan = store.read("RF-AS1");
        alteredPlan.setPlanJson("{}");
        assertThatThrownBy(() -> store.save(alteredPlan)).isInstanceOf(BizException.class);

        current.setStatus(OrdRefundExecution.SUCCEEDED);
        current.setSucceededAt(101L);
        store.save(current);
        assertThat(store.due(200, 5)).hasSize(1);
        current.setResourceStatus(OrdRefundExecution.DONE);
        store.save(current);
        assertThat(store.due(200, 5)).isEmpty();
        assertThat(store.read("RF-AS1").getSucceededAt()).isEqualTo(101L);
        assertThat(quantity.succeeded("MAIN", "SUB1", Instant.ofEpochMilli(100), List.of())).isEmpty();
        assertThat(quantity.succeeded("MAIN", "SUB1", Instant.ofEpochMilli(101), List.of()))
                .singleElement().satisfies(fact -> {
                    assertThat(fact.executionNo()).isEqualTo("RF-AS1");
                    assertThat(fact.lines()).singleElement().satisfies(line -> {
                        assertThat(line.itemId()).isEqualTo(17L);
                        assertThat(line.qty()).isEqualTo(2);
                    });
                });
        assertThat(quantity.succeeded("MAIN", "SUB1", Instant.ofEpochMilli(101), List.of("RF-AS1"))).isEmpty();
    }

    private static void createH2Table(JdbcTemplate jdbc) {
        jdbc.execute("""
                CREATE TABLE ord_refund_obligation (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    execution_no VARCHAR(32),
                    after_sale_no VARCHAR(32),
                    sub_order_no VARCHAR(32),
                    kind VARCHAR(24),
                    source_no VARCHAR(32),
                    currency VARCHAR(8),
                    amount_minor BIGINT,
                    state VARCHAR(10),
                    payload_json MEDIUMTEXT,
                    result_json MEDIUMTEXT,
                    input_hash CHAR(64),
                    authority_proof VARCHAR(128),
                    error_code VARCHAR(128),
                    next_attempt_at BIGINT NOT NULL,
                    finished_at BIGINT,
                    UNIQUE (execution_no, kind, source_no)
                )
                """);
    }
}
