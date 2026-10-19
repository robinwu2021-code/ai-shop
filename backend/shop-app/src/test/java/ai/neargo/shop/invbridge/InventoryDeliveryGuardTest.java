package ai.neargo.shop.invbridge;

import org.junit.jupiter.api.Test;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

class InventoryDeliveryGuardTest {
    @Test void understandsLegacyAndPerHandlerUnifiedResultsAndExactSku() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:guard;DB_CLOSE_DELAY=-1");
        JdbcTemplate db = new JdbcTemplate(ds);
        db.execute("CREATE TABLE sys_outbox(event_no varchar primary key,event_type varchar,payload varchar,status varchar,aggregate_id varchar)");
        db.execute("CREATE TABLE sys_outbox_delivery(event_no varchar,handler_id varchar,status varchar)");
        db.execute("CREATE TABLE prd_stock_lock(lock_no varchar,sku_no varchar)");
        var guard = new InventoryDeliveryGuard(ds, new ObjectMapper());
        db.update("INSERT INTO sys_outbox VALUES('1','INV_MIRROR_RESERVE',?, 'FAILED', 'L1')", "{\"items\":[{\"skuNo\":\"K\"}]}");
        assertThat(guard.blocked("K")).isTrue();
        assertThat(guard.blocked("KK")).isFalse();
        db.update("INSERT INTO sys_outbox_delivery VALUES('1','inventory-mirror','SENT')");
        assertThat(guard.blocked("K")).isFalse();
        db.update("UPDATE sys_outbox SET status='SENT'");
        db.update("UPDATE sys_outbox_delivery SET status='PROCESSING'");
        assertThat(guard.blocked("K")).isTrue();
        db.update("UPDATE sys_outbox_delivery SET status='FAILED'");
        assertThat(guard.blocked("K")).isTrue();
        db.update("UPDATE sys_outbox_delivery SET status='WARNING'");
        assertThat(guard.blocked("K")).isTrue();
        db.update("UPDATE sys_outbox_delivery SET status='SENT'");
        db.update("INSERT INTO sys_outbox VALUES('2','INV_MIRROR_ADJUST','invalid','PENDING','OTHER')");
        assertThat(guard.blocked("K")).as("unrelated malformed messages cannot block the whole platform").isFalse();
        db.update("INSERT INTO sys_outbox VALUES('3','INV_MIRROR_RESERVE','invalid','PENDING','L3')");
        db.update("INSERT INTO prd_stock_lock VALUES('L3','K')");
        assertThat(guard.blocked("K")).as("original lock identifies the affected SKU even with malformed payload").isTrue();
    }
}
