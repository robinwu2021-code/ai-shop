package ai.neargo.shop.support;

import ai.neargo.shop.pay.service.PaymentLedgerService;
import ai.neargo.shop.spi.settle.SettlePort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.UUID;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;

/** Real persisted payment/child/item facts; production shipping creates the package and event. */
public final class OrderLogisticsFixture {
    private OrderLogisticsFixture() { }

    public static void push(MockMvc mvc, String number, String state, boolean valid) throws Exception {
        push(mvc, number, state, valid, "shentong");
    }

    public static void push(MockMvc mvc, String number, String state, boolean valid, String company) throws Exception {
        push(mvc, number, state, valid, company, null);
    }

    public static void push(MockMvc mvc, String number, String state, boolean valid, String company,
                            String callbackId) throws Exception {
        String param = "{\"status\":\"polling\",\"lastResult\":{\"message\":\"ok\",\"state\":\""
                + state + "\",\"com\":\"" + company + "\",\"nu\":\"" + number
                + "\",\"data\":[{\"time\":\"2026-10-15 10:00:00\",\"context\":\"可信渠道节点\"}]}}";
        String sign = HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("MD5")
                .digest((param + "test-kuaidi100-salt").getBytes(StandardCharsets.UTF_8)));
        var request = post("/callback/logistics/kuaidi100").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("param", param).param("sign", valid ? sign : "WRONG");
        if (callbackId != null) request.param("callbackId", callbackId);
        mvc.perform(request);
    }

    /** Test-only accepted /poll identity; old-number or old-carrier pushes remain bare and are rejected. */
    public static void pushForCurrentShipment(JdbcTemplate jdbc, MockMvc mvc, String number,
                                              String state, boolean valid, String company) throws Exception {
        String carrier = switch (company) {
            case "shentong" -> "STO";
            case "yuantong" -> "YTO";
            default -> company;
        };
        var current = jdbc.queryForList("SELECT shipment_no,source_shipment_no,source_shipment_version "
                + "FROM lgs_waybill WHERE waybill_no=? AND carrier=?", number, carrier);
        if (current.isEmpty()) {
            push(mvc, number, state, valid, company);
            return;
        }
        var row = current.getFirst();
        String shipmentNo = (String) row.get("shipment_no");
        String identity = row.get("source_shipment_no") + ":" + row.get("source_shipment_version");
        String month = YearMonth.now().format(DateTimeFormatter.ofPattern("yyyyMM"));
        String callbackId = jdbc.query("SELECT callback_id FROM lgs_kd100_sub_attempt "
                + "WHERE shipment_no=? AND identity_key=? AND company_code=? AND waybill_no=? "
                + "AND callback_id IS NOT NULL ORDER BY id DESC LIMIT 1",
                rs -> rs.next() ? rs.getString(1) : null, shipmentNo, identity, company, number);
        if (callbackId == null) {
            callbackId = UUID.randomUUID().toString().replace("-", "");
            Long used = jdbc.queryForObject("SELECT COUNT(*) FROM lgs_kd100_sub_attempt "
                    + "WHERE account_ref='fixture' AND company_code=? AND waybill_no=? AND month_key=?",
                    Long.class, company, number, month);
            jdbc.update("INSERT INTO lgs_kd100_sub_attempt "
                    + "(account_ref,company_code,waybill_no,month_key,attempt_no,shipment_no,identity_key,"
                    + "callback_id,result,created_at,updated_at) VALUES "
                    + "('fixture',?,?,?,?,?,?,?,'OK',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                    company, number, month, used.intValue() + 1, shipmentNo, identity, callbackId);
        }
        push(mvc, number, state, valid, company, callbackId);
    }

    public static String seed(JdbcTemplate jdbc, PaymentLedgerService ledger, boolean paidLedger) {
        return seed(jdbc, ledger, paidLedger, "LG-BUYER", "EXPRESS");
    }

    public static String seed(JdbcTemplate jdbc, PaymentLedgerService ledger, boolean paidLedger,
                              String buyer, String fulfillment) {
        String sub = "LG" + UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO ord_order (order_no,user_no,status,pay_amount,created_at,updated_at) "
                + "VALUES (?,?,'PAID',1000,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", "O-" + sub, buyer);
        jdbc.update("INSERT INTO ord_sub_order (sub_order_no,order_no,user_no,entity_no,store_no,fulfillment,"
                + "status,pay_amount,receiver_phone,created_at,updated_at) "
                + "VALUES (?,?,?,?,?,?,'WAIT_FULFILL',1000,'13900000099',"
                + "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", sub, "O-" + sub, buyer, "LG-MERCHANT", "LG-STORE", fulfillment);
        jdbc.update("INSERT INTO ord_item (sub_order_no,order_no,goods_no,sku_no,title,qty,created_at,updated_at) "
                + "VALUES (?,?,'LG-GOODS','LG-SKU','物流商品',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", sub, "O-" + sub);
        if (paidLedger) {
            String out = ledger.open(new SettlePort.PaymentOpen("O-" + sub, buyer, "LG-MERCHANT",
                    "TEST", 1000L, "物流测试"));
            ledger.settle(new SettlePort.PaymentSettled(out, "TEST", "TX-" + out, 1000L,
                    System.currentTimeMillis()));
            jdbc.update("UPDATE ord_order SET pay_trade_no=? WHERE order_no=?", "TX-" + out, "O-" + sub);
            ledger.recordPayer(out, "LG-OPENID", "LG-APPID");
        }
        return sub;
    }
}
