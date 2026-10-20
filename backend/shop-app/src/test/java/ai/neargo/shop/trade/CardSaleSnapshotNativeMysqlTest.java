package ai.neargo.shop.trade;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/** Runs V428 and verifies the real MySQL uniqueness and one-way payment-fact update. */
@EnabledIfEnvironmentVariable(named = "CARD_SNAPSHOT_NATIVE_MYSQL_URL", matches = "jdbc:mysql://127\\.0\\.0\\.1:.*")
class CardSaleSnapshotNativeMysqlTest {
    private static HikariDataSource dataSource;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void prepare() {
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(System.getenv("CARD_SNAPSHOT_NATIVE_MYSQL_URL"));
        dataSource.setUsername("root");
        dataSource.setPassword("");
        jdbc = new JdbcTemplate(dataSource);
        new ResourceDatabasePopulator(new ClassPathResource(
                "db/migration/V428__card_sale_entitlement_snapshot.sql")).execute(dataSource);
    }

    @AfterAll
    static void close() { if (dataSource != null) dataSource.close(); }

    @Test
    void sourceAndUnitAreUniqueAndPaidAtCanOnlyBeStampedOnce() {
        LocalDateTime created = LocalDateTime.of(2026, 10, 18, 10, 0);
        insertSnapshot("SRC-1", "LINE-1", 1, 1, created);
        assertThat(jdbc.update("INSERT IGNORE INTO ord_card_sale_snapshot (tenant_no,snapshot_no,source_key,order_no,"
                        + "sub_order_no,order_line_no,unit_index,entitlement_index,user_no,entity_no,store_no,goods_no,"
                        + "sku_no,template_version_no,unit_price_minor,currency,rule_snapshot,snapshot_hash,version,deleted,"
                        + "created_at,updated_at,created_by,updated_by) VALUES ('MAIN','SS-RETRY','SRC-1','ORDER-1',"
                        + "'SUB-1','LINE-1',1,1,'USER-1','ENTITY-1','STORE-1','GOODS-1','SKU-1','VERSION-1',1000,'CNY',"
                        + "CAST('{\"total\":10}' AS JSON),?,0,0,?,?, 'SYSTEM','SYSTEM')",
                "a".repeat(64), Timestamp.valueOf(created), Timestamp.valueOf(created))).isZero();

        LocalDateTime paidAt = LocalDateTime.of(2026, 10, 18, 10, 1, 2, 123_000_000);
        LocalDateTime lateDelivery = paidAt.plusHours(2);
        assertThat(markPaid("ORDER-1", "WX-1", paidAt, lateDelivery)).isOne();
        assertThat(markPaid("ORDER-1", "WX-REPLAY", lateDelivery, lateDelivery)).isZero();
        assertThat(jdbc.queryForObject("SELECT paid_fact_no FROM ord_card_sale_snapshot WHERE source_key='SRC-1'",
                String.class)).isEqualTo("WX-1");
        assertThat(jdbc.queryForObject("SELECT paid_at FROM ord_card_sale_snapshot WHERE source_key='SRC-1'",
                Timestamp.class).toLocalDateTime()).isEqualTo(paidAt);
    }

    private int markPaid(String orderNo, String paidFact, LocalDateTime paidAt, LocalDateTime updatedAt) {
        return jdbc.update("UPDATE ord_card_sale_snapshot SET paid_fact_no=?,paid_at=?,updated_at=?,updated_by='SYSTEM',"
                + "version=version+1 WHERE tenant_no='MAIN' AND order_no=? AND paid_fact_no IS NULL AND deleted=0",
                paidFact, Timestamp.valueOf(paidAt), Timestamp.valueOf(updatedAt), orderNo);
    }

    private void insertSnapshot(String sourceKey, String lineNo, int unit, int entitlement, LocalDateTime created) {
        jdbc.update("INSERT INTO ord_card_sale_snapshot (tenant_no,snapshot_no,source_key,order_no,sub_order_no,order_line_no,"
                        + "unit_index,entitlement_index,user_no,entity_no,store_no,goods_no,sku_no,template_version_no,"
                        + "unit_price_minor,currency,rule_snapshot,snapshot_hash,version,deleted,created_at,updated_at,"
                        + "created_by,updated_by) VALUES ('MAIN',?,?,?,?,?,?,?,?,?,?,?,?,?,1000,'CNY',CAST(? AS JSON),?,0,0,?,?,"
                        + "'SYSTEM','SYSTEM')",
                "SS-1", sourceKey, "ORDER-1", "SUB-1", lineNo, unit, entitlement, "USER-1", "ENTITY-1", "STORE-1",
                "GOODS-1", "SKU-1", "VERSION-1", "{\"total\":10}", "a".repeat(64),
                Timestamp.valueOf(created), Timestamp.valueOf(created));
    }
}
