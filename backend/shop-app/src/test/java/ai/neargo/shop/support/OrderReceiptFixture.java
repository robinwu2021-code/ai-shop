package ai.neargo.shop.support;

import ai.neargo.shop.trade.order.command.OrderActor;
import ai.neargo.shop.trade.order.command.ShipCommand;
import ai.neargo.shop.trade.order.service.OrderShippingService;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/** Persisted historical facts and real shipping commands keep receipt tests on production stores. */
public final class OrderReceiptFixture {
    private OrderReceiptFixture() { }

    public static String seed(JdbcTemplate jdbc, String buyer) {
        String sub = "RC" + UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO ord_order (order_no,user_no,status,created_at,updated_at) "
                + "VALUES (?,?,'PAID',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", "O-" + sub, buyer);
        jdbc.update("INSERT INTO ord_sub_order (sub_order_no,order_no,user_no,entity_no,store_no,fulfillment,"
                + "status,created_at,updated_at) VALUES (?,?,?,'RC-MERCHANT','RC-STORE','EXPRESS','WAIT_FULFILL',"
                + "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", sub, "O-" + sub, buyer);
        item(jdbc, sub, null);
        return sub;
    }

    public static String ship(OrderShippingService shipping, String sub) {
        return shipping.ship(sub, new ShipCommand(List.of(), "SF", "SF" + sub.substring(2, 22).toUpperCase()),
                new OrderActor("RC-MERCHANT", "RC-STORE")).shipmentId();
    }

    public static void item(JdbcTemplate jdbc, String sub, String shipment) {
        jdbc.update("INSERT INTO ord_item (sub_order_no,order_no,goods_no,sku_no,qty,shipment_no,shipped_qty,"
                + "created_at,updated_at) VALUES (?,?,'RC-GOODS','RC-SKU',1,?,?,"
                + "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", sub, "O-" + sub, shipment, shipment == null ? 0 : 1);
    }

    public static String extraPackage(JdbcTemplate jdbc, String sub) {
        String shipment = "SH" + UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO ord_shipment (shipment_no,sub_order_no,ship_store_no,fulfillment,carrier,"
                + "waybill_no,status,created_at,updated_at) VALUES (?,?,'RC-STORE','EXPRESS','SF',?,'SHIPPED',"
                + "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                shipment, sub, "SF" + shipment.substring(2, 22).toUpperCase());
        item(jdbc, sub, shipment);
        return shipment;
    }

    public static void due(JdbcTemplate jdbc, String sub, long at) {
        jdbc.update("UPDATE ord_status_log SET at=? WHERE sub_order_no=? AND status='FULFILLING'", at, sub);
        jdbc.update("UPDATE ord_shipment SET created_at=? WHERE sub_order_no=?", physicalAt(at), sub);
    }

    /** Match production audit fields; JDBC Timestamp would shift fixtures through the driver's UTC conversion. */
    public static LocalDateTime physicalAt(long at) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(at), ZoneId.systemDefault());
    }
}
