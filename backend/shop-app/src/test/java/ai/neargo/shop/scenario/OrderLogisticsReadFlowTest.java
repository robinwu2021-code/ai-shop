package ai.neargo.shop.scenario;

import ai.neargo.shop.event.OutboxDispatcher;
import ai.neargo.shop.logistics.capability.StatusProbe;
import ai.neargo.shop.logistics.routing.ChannelRouter;
import ai.neargo.shop.pay.service.PaymentLedgerService;
import ai.neargo.shop.spi.logistics.TraceResult;
import ai.neargo.shop.trade.service.MerchantOrderService;
import ai.neargo.shop.logistics.config.LogisticsProperties;
import ai.neargo.shop.spi.logistics.TraceStatus;
import ai.neargo.shop.support.OrderLogisticsFixture;
import ai.neargo.shop.support.TestLogin;
import ai.neargo.shop.trade.order.command.OrderActor;
import ai.neargo.shop.trade.order.command.ShipCommand;
import ai.neargo.shop.trade.order.service.OrderShippingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** Ordinary MP detail must stay local, while the dedicated WX trace lookup retains its 10-minute gate. */
@SpringBootTest
@ActiveProfiles("test")
class OrderLogisticsReadFlowTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PaymentLedgerService ledger;
    @Autowired private OrderShippingService shipping;
    @Autowired private OutboxDispatcher dispatcher;
    @Autowired private ChannelRouter router;
    @Autowired private ObjectMapper json;
    @Autowired private WebApplicationContext context;
    @Autowired private MerchantOrderService merchant;
    @Autowired private LogisticsProperties properties;

    @Test
    void ordinaryDetailReadsCacheAndDedicatedWxTraceChecksOnce() throws Exception {
        String sub = OrderLogisticsFixture.seed(jdbc, ledger, false);
        String number = "STO" + sub.substring(2, 22).toUpperCase(Locale.ROOT);
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        String token = TestLogin.consumerByWechat(mvc, json, "read-" + sub);
        String profile = mvc.perform(get("/mp/user/profile").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        String user = json.readTree(profile).path("data").path("cUserNo").asString("");
        jdbc.update("UPDATE ord_order SET user_no=? WHERE order_no=?", user, "O-" + sub);
        jdbc.update("UPDATE ord_sub_order SET user_no=? WHERE sub_order_no=?", user, sub);
        shipping.ship(sub, new ShipCommand(List.of(), "STO", number), new OrderActor("LG-MERCHANT", "LG-STORE"));
        for (int i = 0; i < 8; i++) { dispatcher.dispatchPending(); }
        jdbc.update("UPDATE lgs_waybill SET profile='WX',display_token='LOCAL-TOKEN',wx_status_checked_at=NULL "
                + "WHERE biz_ref=?", sub);
        AtomicInteger queries = new AtomicInteger();
        StatusProbe fake = new StatusProbe() {
            public String channel() { return "wx"; }
            public boolean coversAllCarriers() { return true; }
            public Optional<TraceResult> probe(ProbeCmd command) {
                queries.incrementAndGet();
                return Optional.of(new TraceResult(number, "STO", TraceStatus.IN_TRANSIT, "wx", List.of()));
            }
        };
        StatusProbe original = router.installedProbes().put("wx", fake);
        try {
            String detail = mvc.perform(get("/mp/order/" + sub).header("X-Client", "MP")
                    .header("Authorization", "Bearer " + token)).andReturn().getResponse().getContentAsString();
            assertThat(json.readTree(detail).path("code").asInt(-1)).isZero();
            assertThat(queries.get()).as("普通详情不能消耗微信查询次数").isZero();
            for (int i = 0; i < 2; i++) {
                mvc.perform(get("/mp/order/" + sub + "/trace").header("X-Client", "MP")
                        .header("Authorization", "Bearer " + token));
            }
            assertThat(queries.get()).as("专用物流页十分钟内只校正一次").isEqualTo(1);
        } finally {
            router.installedProbes().put("wx", original);
        }
    }
    @Test
    void consumerMerchantAndProtectedOpsReadSameCachedExceptionAndNodes() throws Exception {
        String sub = OrderLogisticsFixture.seed(jdbc, ledger, false);
        String number = "STO" + sub.substring(2, 22).toUpperCase(Locale.ROOT);
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        String buyer = TestLogin.consumerByWechat(mvc, json, "shared-" + sub);
        String user = json.readTree(mvc.perform(get("/mp/user/profile")
                .header("Authorization", "Bearer " + buyer)).andReturn().getResponse().getContentAsString())
                .path("data").path("cUserNo").asString("");
        jdbc.update("UPDATE ord_order SET user_no=? WHERE order_no=?", user, "O-" + sub);
        jdbc.update("UPDATE ord_sub_order SET user_no=? WHERE sub_order_no=?", user, sub);
        shipping.ship(sub, new ShipCommand(List.of(), "STO", number), new OrderActor("LG-MERCHANT", "LG-STORE"));
        for (int i = 0; i < 8; i++) { dispatcher.dispatchPending(); }
        jdbc.update("UPDATE lgs_waybill SET sub_channel='kuaidi100',sub_state='DONE',profile='WX',"
                + "display_token='LOCAL-TOKEN',wx_status_checked_at=NULL WHERE biz_ref=?", sub);
        OrderLogisticsFixture.pushForCurrentShipment(jdbc, mvc, number, "2", true, "shentong");
        for (int i = 0; i < 8; i++) { dispatcher.dispatchPending(); }
        AtomicInteger queries = new AtomicInteger();
        StatusProbe wx = countingProbe("wx", queries);
        StatusProbe kd100 = countingProbe("kuaidi100", queries);
        StatusProbe originalWx = router.installedProbes().put("wx", wx);
        StatusProbe originalKd100 = router.installedProbes().put("kuaidi100", kd100);
        List<String> originalRoute = properties.getRoutes().getProbe().getByStore()
                .put("LG-STORE", List.of("wx", "kuaidi100"));
        try {
            var consumer = json.readTree(mvc.perform(get("/mp/order/" + sub).header("X-Client", "MP")
                    .header("Authorization", "Bearer " + buyer)).andReturn().getResponse().getContentAsString())
                    .path("data").path("trace");
            var business = json.valueToTree(merchant.detail("LG-MERCHANT", "LG-STORE", sub)).path("trace");
            String admin = TestLogin.admin(mvc, json);
            var ops = json.readTree(mvc.perform(get("/ops/shipments").param("keyword", sub)
                    .header("Authorization", "Bearer " + admin)).andReturn().getResponse().getContentAsString())
                    .path("data").path("records").get(0);
            assertCommonViews(consumer, business, ops, number);
            assertThat(queries.get()).as("三端普通详情不能调用微信或快递100查询").isZero();
        } finally {
            router.installedProbes().put("wx", originalWx);
            if (originalKd100 == null) { router.installedProbes().remove("kuaidi100"); }
            else { router.installedProbes().put("kuaidi100", originalKd100); }
            if (originalRoute == null) { properties.getRoutes().getProbe().getByStore().remove("LG-STORE"); }
            else { properties.getRoutes().getProbe().getByStore().put("LG-STORE", originalRoute); }
        }
    }

    private static void assertCommonViews(JsonNode consumer, JsonNode business, JsonNode ops, String number) {
        assertThat(consumer.path("status").asString("")).isEqualTo("EXCEPTION");
        assertThat(business.path("status").asString("")).isEqualTo(consumer.path("status").asString());
        assertThat(ops.path("status").asString("")).isEqualTo(consumer.path("status").asString());
        assertThat(business.path("waybillNo").asString("")).isEqualTo(number);
        assertThat(ops.path("waybillNo").asString("")).isEqualTo(number);
        String text = consumer.path("nodes").get(0).path("text").asString();
        assertThat(text).isEqualTo("可信渠道节点");
        assertThat(business.path("nodes").get(0).path("text").asString()).isEqualTo(text);
        assertThat(ops.path("traces").get(0).path("text").asString()).isEqualTo(text);
    }

    private static StatusProbe countingProbe(String name, AtomicInteger queries) {
        return new StatusProbe() {
            public String channel() { return name; }
            public boolean coversAllCarriers() { return true; }
            public Optional<TraceResult> probe(ProbeCmd command) {
                queries.incrementAndGet();
                throw new AssertionError("普通详情不应触发渠道查询: " + name);
            }
        };
    }

}
