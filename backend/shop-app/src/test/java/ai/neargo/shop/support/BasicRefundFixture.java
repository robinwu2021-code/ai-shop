package ai.neargo.shop.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ai.neargo.shop.common.OtpStore;
import ai.neargo.shop.pay.channel.TestPayGateway;
import ai.neargo.shop.spi.pay.PayQueryPort;
import ai.neargo.shop.support.port.FakePayQueryPort;
import ai.neargo.shop.trade.service.AfterSaleService;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Real C/B service fixture; only the external test channel is controlled by the test. */
public final class BasicRefundFixture implements AutoCloseable {
    private final MockMvc mvc;
    private final ObjectMapper json;
    private final OtpStore otp;
    private final JdbcTemplate jdbc;
    private final TestPayGateway gateway;
    private final AfterSaleService afterSales;
    private final FakePayQueryPort query;
    private final String fundsMode;
    private final ai.neargo.shop.trade.service.MerchantOrderService merchantOrders;

    public BasicRefundFixture(WebApplicationContext context) {
        merchantOrders = context.getBean(ai.neargo.shop.trade.service.MerchantOrderService.class);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        json = context.getBean(ObjectMapper.class);
        otp = context.getBean(OtpStore.class);
        jdbc = context.getBean(JdbcTemplate.class);
        gateway = context.getBean(TestPayGateway.class);
        afterSales = context.getBean(AfterSaleService.class);
        query = context.getBean(FakePayQueryPort.class);
        fundsMode = jdbc.queryForObject("select funds_mode from mch_entity where entity_no='M0001'", String.class);
        jdbc.update("update mch_entity set funds_mode='DIRECT' where entity_no='M0001'");
    }

    public Purchase purchase(String phone) throws Exception {
        return purchase(phone, null);
    }

    public String login(String phone) throws Exception {
        return TestLogin.consumer(mvc, json, otp, phone);
    }

    public Purchase purchase(String phone, String couponNo) throws Exception {
        String token = login(phone);
        JsonNode created = request(token, "/mp/order", "{\"items\":[{\"goodsNo\":\"G0002\","
                + "\"skuNo\":\"SK0003\",\"qty\":1}],\"fulfillment\":\"STORE_PICKUP\","
                + "\"pickupNo\":\"PP0001\",\"usePoints\":0"
                + (couponNo == null ? "" : ",\"couponNo\":\"" + couponNo + "\"") + "}");
        assertThat(created.path("code").asInt()).as(created.toString()).isZero();
        String payNo = created.path("data").path("payOrderNo").asString();
        if (!"PAID".equals(jdbc.queryForObject("select status from ord_order where order_no=?",
                String.class, payNo))) {
            pay(token, payNo);
        }
        String subNo = jdbc.queryForObject("select sub_order_no from ord_sub_order where order_no=?",
                String.class, payNo);
        return new Purchase(token, payNo, subNo);
    }

    public void pay(String token, String payNo) throws Exception {
        JsonNode initiated = request(token, "/mp/order/" + payNo + "/pay", "{}");
        assertThat(initiated.path("code").asInt()).as(initiated.toString()).isZero();
        if ("PAID".equals(jdbc.queryForObject("select status from ord_order where order_no=?",
                String.class, payNo))) {
            return;
        }
        String outNo = jdbc.queryForObject("select out_trade_no from stl_payment where order_no=?"
                + " and direction='PAY'", String.class, payNo);
        assertThat(gateway.markPaid(outNo)).isTrue();
        String provider = gateway.query(outNo).tradeNo();
        String ack = mvc.perform(post("/pay/callback/stub").contentType(MediaType.APPLICATION_JSON)
                .content("{\"outTradeNo\":\"" + outNo + "\",\"transactionId\":\"" + provider
                        + "\",\"sign\":\"stub-secret\"}"))
                .andReturn().getResponse().getContentAsString();
        assertThat(ack).isEqualTo("SUCCESS");
        assertThat(jdbc.queryForObject("select status from stl_payment where out_trade_no=?",
                String.class, outNo)).isEqualTo("SUCCESS");
    }

    public String apply(Purchase p, String type) throws Exception {
        JsonNode response = request(p.token(), "/mp/order/" + p.subNo() + "/after-sale",
                "{\"type\":\"" + type + "\",\"reason\":\"不想要了\",\"reasonCode\":\"NOT_WANTED\"}");
        assertThat(response.path("code").asInt()).as(response.toString()).isZero();
        return response.path("data").path("afterSaleNo").asString();
    }

    public void prepare(Purchase p) {
        merchantOrders.prepare(merchant(p), jdbc.queryForObject(
                "select store_no from ord_sub_order where sub_order_no=?", String.class, p.subNo()), p.subNo());
    }

    public void approve(Purchase p, String no) {
        afterSales.approve(merchant(p), no, "full refund approved", null, null);
    }

    public String merchant(Purchase p) {
        return jdbc.queryForObject("select entity_no from ord_sub_order where sub_order_no=?",
                String.class, p.subNo());
    }

    public void confirmCash(String no) throws Exception {
        String outNo = jdbc.queryForObject("select out_trade_no from stl_payment where after_sale_no=?"
                + " and direction='REFUND'", String.class, no);
        var actual = gateway.queryRefund(outNo);
        assertThat(actual.paid()).isTrue();
        query.answer(new PayQueryPort.Result(actual.ok(), actual.paid(), actual.found(),
                actual.amountMinor(), actual.tradeNo(), actual.currency()));
        String ack = mvc.perform(post("/pay/callback/TEST/refund").contentType(MediaType.APPLICATION_JSON)
                .content("verified:" + outNo)).andReturn().getResponse().getContentAsString();
        assertThat(ack).isEqualTo("SUCCESS");
    }

    public JsonNode request(String token, String path, String body) throws Exception {
        return json.readTree(mvc.perform(post(path).header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .content(body)).andReturn().getResponse().getContentAsString());
    }

    @Override
    public void close() {
        query.reset();
        jdbc.update("update mch_entity set funds_mode=? where entity_no='M0001'", fundsMode);
    }

    public record Purchase(String token, String payNo, String subNo) { }
}
