package ai.neargo.commerce.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import static org.junit.jupiter.api.Assertions.*;

class MallOrderDatabaseMysqlTest {
    @Test void placesQuotedRowsOnceAndPreservesOriginalAccountAndFifoFences() {
        try (var db = new MysqlDatabase()) {
            db.migrate();
            db.jdbc.execute("DROP TABLE mall_order,mall_order_item,mall_order_component,mall_quote");
            new ResourceDatabasePopulator(new ClassPathResource("db/mall/order/V1__mall_order.sql")).execute(db.ds);
            var f = new MallOrderConcurrencyMysqlTest.Fixture(db,false,null,9000,true);
            db.jdbc.execute("DROP TABLE commerce_event_inbox");
            var quote = f.quote();
            var orderNo = db.jdbc.queryForObject("SELECT order_no FROM mall_order WHERE stage='QUOTE'", String.class);
            var components = db.jdbc.queryForList("SELECT order_component_no FROM mall_order_component ORDER BY id",String.class);
            assertFalse(components.isEmpty());
            var repo = new JdbcMallRepository(db.ds,new JdbcCommerceEventPersistence(db.ds),f.c.ids);
            f.c.uow.required(() -> {
                assertTrue(repo.orders(MallOrderConcurrencyMysqlTest.STORE,null,10).isEmpty());
                assertTrue(repo.order("tenant-a",orderNo).isEmpty());
                return null;
            });
            assertTrue(new JdbcRecoveryRepository(db.ds,true).orders(0,10).isEmpty());
            db.jdbc.update("UPDATE mall_order SET store_no='no-history' WHERE order_no=?",orderNo);
            var relink = new JdbcStoreRelinkRepository(db.ds,true);
            var otherStore = new ai.neargo.commerce.api.common.StoreRef("tenant-a","no-history");
            assertFalse(relink.inspect(otherStore).hasPaidHistory());
            db.jdbc.update("UPDATE mall_order SET store_no='store' WHERE order_no=?",orderNo);
            var other = f.quote();
            var placed = f.place(quote,"key-1");
            assertEquals(orderNo,placed.orderNo());
            assertEquals(placed,f.place(quote,"key-1"));
            assertEquals("ORDERED",db.jdbc.queryForObject("SELECT stage FROM mall_order WHERE stage='ORDERED'",String.class));
            assertEquals(components,db.jdbc.queryForList("SELECT c.order_component_no FROM mall_order_component c JOIN mall_order_item i ON i.tenant_no=c.tenant_no AND i.order_item_no=c.order_item_no WHERE i.order_no=? ORDER BY c.id",String.class,orderNo));
            assertEquals(1,db.jdbc.queryForObject("SELECT COUNT(*) FROM mch_subscription_reservation",Integer.class));
            assertEquals(200,db.jdbc.queryForObject("SELECT reserved_minor FROM mch_subscription_credit_account",Integer.class));
            assertEquals(2,db.jdbc.queryForObject("SELECT COUNT(*) FROM mch_subscription_credit_ledger",Integer.class));
            assertEquals("IDEMPOTENCY_CONFLICT", assertThrows(ai.neargo.commerce.api.mall.MallException.class,() -> f.place(other,"key-1")).code());
            f.c.uow.required(() -> { new JdbcRecoveryRepository(db.ds,true).expireQuotes(10); return null; });
            assertEquals("QUOTE_EXPIRED", assertThrows(ai.neargo.commerce.api.mall.MallException.class,() -> f.place(other,"fresh")).code());
            assertEquals("ORDERED",db.jdbc.queryForObject("SELECT stage FROM mall_order WHERE order_no=?",String.class,orderNo));
        }
    }
}
