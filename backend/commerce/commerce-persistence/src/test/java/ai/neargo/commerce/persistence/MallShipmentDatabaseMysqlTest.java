package ai.neargo.commerce.persistence;

import ai.neargo.commerce.api.fulfillment.HardwareShipmentCommand;
import ai.neargo.commerce.api.port.InventoryCommand;
import ai.neargo.commerce.core.fulfillment.FulfillmentRepository.Operation;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import static org.junit.jupiter.api.Assertions.*;

class MallShipmentDatabaseMysqlTest {
    @Test void writesSerializedItemsWithTheirOrderIdentityAndRollsBackDuplicateDevice() {
        try (var db = new MysqlDatabase()) {
            var fixture = new MallFulfillmentMysqlTest().setup(db,true);
            var mall = fixture.mall();
            var tenant = MallOrderConcurrencyMysqlTest.STORE.tenantNo();
            String order = fixture.order().orderNo();
            var orders = new JdbcMallRepository(db.ds,new JdbcCommerceEventPersistence(db.ds));
            var snapshot = mall.c.uow.required(() -> orders.order(tenant,order).orElseThrow());
            var component = db.jdbc.queryForObject("""
                    SELECT order_component_no FROM mall_order_component WHERE component_type='HARDWARE'
                    """,String.class);
            db.jdbc.execute("DROP TABLE mall_shipment,mall_fulfillment_task,mall_payment_ref");
            new ResourceDatabasePopulator(new ClassPathResource("db/mall/fulfillment/V1__mall_fulfillment.sql")).execute(db.ds);
            var repo = new JdbcFulfillmentRepository(db.ds,true);
            var inventory = new InventoryCommand(tenant,order,component,"hardware",2);
            java.util.function.Consumer<String> ship = no -> mall.c.uow.required(() -> {
                var cmd = new HardwareShipmentCommand(tenant,order,component,no,"carrier","tracking",2,"ops",List.of("SN01","SN02"));
                var operation = new Operation(no,order,component,"SHIP",null,inventory,cmd,null,null);
                repo.prepareShipment(snapshot,operation);
                repo.shipment(snapshot,operation);
                return null;
            });
            ship.accept("shipment-1");
            assertEquals(List.of("SN01","SN02"),db.jdbc.queryForList(
                    "SELECT device_sn FROM mall_shipment_item ORDER BY line_no",String.class));
            assertEquals(2,db.jdbc.queryForObject("""
                    SELECT COUNT(*) FROM mall_shipment_item s JOIN mall_order_item i
                    ON s.tenant_no=i.tenant_no AND s.order_item_no=i.order_item_no AND s.sku_no=i.sku_no
                    WHERE s.qty=1 AND s.sku_name_snapshot=i.sku_snapshot->>'$.sku.title'
                    """,Integer.class));
            assertThrows(DuplicateKeyException.class,() -> ship.accept("shipment-2"));
            assertEquals(1,db.jdbc.queryForObject("SELECT COUNT(*) FROM mall_shipment",Integer.class));
        }
    }
}
