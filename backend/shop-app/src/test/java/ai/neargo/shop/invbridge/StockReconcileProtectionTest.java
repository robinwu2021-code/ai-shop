package ai.neargo.shop.invbridge;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class StockReconcileProtectionTest {
    @Test void repeatedOperationNumberReturnsPersistedResultWithoutRepeatingWriteback() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:reconcile;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE prd_stock_sync_operation (operation_no varchar(96) primary key, entity_no varchar(64) not null, store_no varchar(64) not null, sku_no varchar(64) not null, reason varchar(32) not null, request_hash varchar(64) not null, status varchar(16) not null, result_json clob, updated_at timestamp not null)");
        var writeback = mock(InventoryWritebackService.class);
        when(writeback.syncOne("E", "S", "K", "OP-1")).thenReturn(
                new ai.neargo.shop.product.service.StockSyncService.Result(true, null, "ALL", 4, 2, 4));
        var service = new StockReconcileService(jdbc, writeback, new tools.jackson.databind.ObjectMapper(),numbers(jdbc));

        var first = service.reconcile("E", "S", "K", "MANUAL", "OP-1");
        var repeated = service.reconcile("E", "S", "K", "MANUAL", "OP-1");

        assertThat(first).isEqualTo(repeated);
        assertThat(ai.neargo.shop.numbering.DocumentNumberFormatter.isValid(
            ai.neargo.shop.numbering.DocumentType.INVENTORY_SYNC,first.documentNo())).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM core_document_number",Integer.class)).isEqualTo(1);
        verify(writeback, times(1)).syncOne("E", "S", "K", "OP-1");
    }

    @Test void operationNumberCannotBeReusedForDifferentRequest() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:reconcile-conflict;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE prd_stock_sync_operation (operation_no varchar(96) primary key, entity_no varchar(64) not null, store_no varchar(64) not null, sku_no varchar(64) not null, reason varchar(32) not null, request_hash varchar(64) not null, status varchar(16) not null, result_json clob, updated_at timestamp not null)");
        var writeback = mock(InventoryWritebackService.class);
        when(writeback.syncOne(anyString(), anyString(), anyString(), anyString())).thenReturn(
                new ai.neargo.shop.product.service.StockSyncService.Result(true, null, "ALL", 4, 2, 4));
        var service = new StockReconcileService(jdbc, writeback, new tools.jackson.databind.ObjectMapper(),numbers(jdbc));
        service.reconcile("E", "S", "K", "MANUAL", "OP-2");

        assertThatThrownBy(() -> service.reconcile("E", "S", "OTHER", "MANUAL", "OP-2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("operationNo");
        verify(writeback, times(1)).syncOne(anyString(), anyString(), anyString(), anyString());
    }

    @Test void merchantHistoryIsScopedPagedAndOmitsPersistedRawPayload() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:reconcile-records;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE prd_stock_sync_operation (operation_no varchar(96) primary key, entity_no varchar(64) not null, store_no varchar(64) not null, sku_no varchar(64) not null, reason varchar(32) not null, request_hash varchar(64) not null, status varchar(16) not null, result_json clob, updated_at timestamp not null)");
        jdbc.update("INSERT INTO prd_stock_sync_operation VALUES ('OP-1','E','S','K1','MANUAL','h','FAILED',null,'2026-10-19 10:00:00')");
        jdbc.update("INSERT INTO prd_stock_sync_operation VALUES ('OP-2','E','S','K2','AUTO','h','COMPLETED','{\"applied\":true,\"skipped\":null,\"ruleType\":\"ALL\",\"available\":4,\"before\":2,\"after\":4}','2026-10-19 11:00:00')");
        jdbc.update("INSERT INTO prd_stock_sync_operation VALUES ('OP-X','OTHER','S','K3','AUTO','h','COMPLETED',null,'2026-10-19 12:00:00')");
        var service = new StockReconcileService(jdbc, mock(InventoryWritebackService.class), new tools.jackson.databind.ObjectMapper(),numbers(jdbc));

        var page = service.records("E", "S", null, 0, 1);
        var filtered = service.records("E", "S", "K1", 0, 20);

        assertThat(page).hasSize(1).extracting(StockReconcileService.OperationRecord::operationNo).containsExactly("OP-2");
        assertThat(page.get(0).result().after()).isEqualTo(4);
        assertThat(filtered).extracting(StockReconcileService.OperationRecord::operationNo).containsExactly("OP-1");
    }
    private static ai.neargo.shop.numbering.store.DocumentNumberCoordinator numbers(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE sys_sequence(seq_name VARCHAR(32) PRIMARY KEY,next_value BIGINT NOT NULL,updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE core_document_number(document_no VARCHAR(32) PRIMARY KEY,tenant_no VARCHAR(64),document_type VARCHAR(40),source_system VARCHAR(32),source_resource_type VARCHAR(64),source_no VARCHAR(128),business_date DATE,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,UNIQUE(tenant_no,document_type,source_system,source_resource_type,source_no))");
        var manager=new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource());
        var repository=new ai.neargo.shop.numbering.store.JdbcDocumentNumberRepository(ai.neargo.shop.numbering.DocumentOwner.CORE,manager);
        var generator=new ai.neargo.shop.numbering.store.SegmentDocumentNumberGenerator(repository,
            new ai.neargo.shop.numbering.store.DocumentNumberProperties(java.time.ZoneId.of("Asia/Shanghai"),3));
        return new ai.neargo.shop.numbering.store.DocumentNumberCoordinator(repository,generator,manager);
    }

}
