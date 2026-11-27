package ai.neargo.shop.paybridge;

import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.shop.paybridge.impl.PaymentCollectionServiceImpl;
import ai.neargo.shop.spi.pay.PaidCollectionPort.PaidCollection;
import ai.neargo.shop.trade.service.StoreOrderSettlementService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** 收款码收款挂门店零售单：回执与挂单同库，在同一事务里直接调，不经发件箱。 */
@DisplayName("收款到账直接挂单")
class PaymentCollectionAttachTest {

    private final EmbeddedDatabase db = new EmbeddedDatabaseBuilder()
            .setType(EmbeddedDatabaseType.H2)
            .setName("collect-" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE")
            .build();
    private final StoreOrderSettlementService settlements = mock(StoreOrderSettlementService.class);
    private final ObjectMapper json = new ObjectMapper();
    private final PaymentCollectionServiceImpl service;

    PaymentCollectionAttachTest() {
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(db);
        jdbc.execute("CREATE TABLE payment_collection_receipt (environment VARCHAR(16), event_id VARCHAR(64),"
                + " pay_order_no VARCHAR(64), payload_sha256 VARCHAR(64), receipt_no VARCHAR(64), payload TEXT,"
                + " created_at BIGINT, store_no VARCHAR(64), entity_no VARCHAR(64), projected_at BIGINT,"
                + " UNIQUE KEY uk_event (environment, event_id))");
        jdbc.execute("CREATE TABLE payment_collection_source (environment VARCHAR(16), pay_app_id BIGINT,"
                + " source_type VARCHAR(16), source_id BIGINT, payment_store_id BIGINT, store_no VARCHAR(64),"
                + " entity_no VARCHAR(64), enabled INT)");
        jdbc.execute("CREATE TABLE mch_store (id BIGINT, tenant_no VARCHAR(16), store_no VARCHAR(64),"
                + " entity_no VARCHAR(64), deleted INT)");
        jdbc.execute("CREATE TABLE mch_entity (tenant_no VARCHAR(16), entity_no VARCHAR(64), market VARCHAR(8),"
                + " deleted INT)");
        jdbc.update("INSERT INTO mch_store VALUES (9001,'MAIN','S1','E1',0)");
        jdbc.update("INSERT INTO mch_entity VALUES ('MAIN','E1','TW',0)");
        service = new PaymentCollectionServiceImpl(db, new DataSourceTransactionManager(db),
                mock(OutboxEventBus.class), new PaymentCollectionProperties(), settlements);
    }

    @AfterEach
    void close() { db.shutdown(); }

    private PaymentCollectionFact fact(long id, String bizType, String bizNo) {
        return new PaymentCollectionFact(1, 2, "PAYMENT_SUCCEEDED|O" + id + "|2", "PAYMENT_SUCCEEDED", 42L,
                "BIZ", 42L, "P" + id, "OUT" + id, bizType, bizNo, 9001L, "TW", "TWD", 100, 100, "PM" + id,
                "OT" + id, "LINEPAY", "CH" + id, System.currentTimeMillis(), "S1", null, null);
    }

    @Test
    @DisplayName("收款码收款：回执写入的同一事务里直接挂单；重复回执不再挂")
    void bizCollectAttachesDirectlyOnce() {
        var fact = fact(1, "BIZ_COLLECT", "SO-1");
        var accepted = service.receive("PRODUCTION", fact, json.writeValueAsString(fact));
        assertThat(accepted.status()).isEqualTo("APPLIED");
        verify(settlements).attachCollection(new PaidCollection("P1", "S1", "SO-1", "TWD", 100, "LINEPAY"));

        assertThat(service.receive("PRODUCTION", fact, json.writeValueAsString(fact)).status())
                .isEqualTo("DUPLICATE");
        verify(settlements, times(1)).attachCollection(any());
    }

    @Test
    @DisplayName("结账码收款不挂门店零售单")
    void checkoutQrDoesNotAttach() {
        var fact = new PaymentCollectionFact(1, 2, "PAYMENT_SUCCEEDED|O2|2", "PAYMENT_SUCCEEDED", 42L,
                "CHECKOUT", 42L, "P2", "OUT2", "CHECKOUT_QR", "QR2", 9001L, "TW", "TWD", 100, 100, "PM2",
                "OT2", "LINEPAY", "CH2", System.currentTimeMillis(), "S1", null, null);
        service.receive("PRODUCTION", fact, json.writeValueAsString(fact));
        verify(settlements, never()).attachCollection(any());
    }
}
