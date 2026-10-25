package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.OtpStore;
import ai.neargo.shop.spi.pay.PaymentPort;
import ai.neargo.shop.spi.pay.PaymentPort.InternalCreatePaymentCmd;
import ai.neargo.shop.support.TestLogin;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * AC3：一笔要收的钱先有一张支付单；通道尝试挂在它下面（TDD-支付服务-I3支付单退款流水 S3）。
 *
 * <p>此前一笔钱的身份就是「订单号 + 一串 stl_payment」：没有一处能回答「这笔钱应收多少、实收多少、退了多少、
 * 现在什么状态」，对账、流水、退款各自从尝试行上拼。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:pay-order-flow;MODE=MySQL;"
        + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class PayOrderFlowTest {

    @Autowired private PaymentPort payments;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper json;
    @Autowired private OtpStore otp;

    private Long anyStore() {
        return jdbc.queryForObject("SELECT id FROM mch_store ORDER BY id LIMIT 1", Long.class);
    }

    private InternalCreatePaymentCmd cmd(String no, long amount) {
        return new InternalCreatePaymentCmd(PaymentPort.MALL_ORDER, no, no, anyStore(), "门店", "主体", "CN", amount,
                "CNY", "goods", System.currentTimeMillis() + 900_000, "AGGREGATED");
    }

    @Test
    @DisplayName("★★★ 同一调用方单号再建：同一张支付单，库里一行")
    void createIsIdempotent() {
        String no = "PO-T-" + UUID.randomUUID().toString().substring(0, 8);
        var first = payments.createPayment(cmd(no, 1990));
        var second = payments.createPayment(cmd(no, 1990));
        assertThat(first).isNotNull();
        assertThat(second.payOrderNo()).isEqualTo(first.payOrderNo());
        assertThat(first.status()).isEqualTo("CREATED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pay_order WHERE out_order_no=?", Integer.class, no))
                .isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT biz_type, source_type, amount_minor, market, request_sha256 FROM pay_order"
                + " WHERE out_order_no=?", no))
                .containsEntry("biz_type", "MALL_ORDER").containsEntry("source_type", "INTERNAL")
                .containsEntry("amount_minor", 1990L).containsEntry("market", "CN");
    }

    @Test
    @DisplayName("★★★ 同号不同内容（金额被改）→ PAY_ORDER_CONFLICT：幂等键不能把另一笔请求吞成旧单")
    void sameKeyDifferentContentRejected() {
        String no = "PO-T-" + UUID.randomUUID().toString().substring(0, 8);
        payments.createPayment(cmd(no, 1990));
        assertThatThrownBy(() -> payments.createPayment(cmd(no, 1)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode()).isEqualTo(ErrorCode.PAY_ORDER_CONFLICT);
    }

    @Test
    @DisplayName("★★★ 商城下单去支付：有支付单（门店 id、应付金额），通道尝试挂上 pay_order_id，支付单进入待支付")
    void mallPaymentHasPayOrder() throws Exception {
        String token = TestLogin.consumer(mvc(), json, otp, "13207031701");
        JsonNode created = request(token, "/mp/order", "{\"items\":[{\"goodsNo\":\"G0002\","
                + "\"skuNo\":\"SK0003\",\"qty\":1}],\"fulfillment\":\"STORE_PICKUP\","
                + "\"pickupNo\":\"PP0001\",\"usePoints\":0}");
        assertThat(created.path("code").asInt()).as(created.toString()).isZero();
        String orderNo = created.path("data").path("payOrderNo").asString();
        assertThat(request(token, "/mp/order/" + orderNo + "/pay", "{}").path("code").asInt()).isZero();

        Map<String, Object> po = jdbc.queryForMap("SELECT * FROM pay_order WHERE out_order_no=?", orderNo);
        Long storeId = jdbc.queryForObject("SELECT s.id FROM mch_store s JOIN ord_sub_order so ON so.store_no = s.store_no"
                + " WHERE so.order_no=? ORDER BY so.sub_order_no LIMIT 1", Long.class, orderNo);
        assertThat(po).containsEntry("biz_type", "MALL_ORDER").containsEntry("status", "AWAITING_PAYMENT")
                .containsEntry("store_id", storeId)
                .containsEntry("amount_minor",
                        jdbc.queryForObject("SELECT pay_amount FROM ord_order WHERE order_no=?", Long.class, orderNo));
        assertThat(jdbc.queryForObject("SELECT pay_order_id FROM stl_payment WHERE order_no=? AND direction='PAY'",
                Long.class, orderNo)).as("通道尝试挂在支付单下").isEqualTo(po.get("id"));
    }

    private JsonNode request(String token, String path, String body) throws Exception {
        return json.readTree(mvc().perform(post(path).header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .content(body)).andReturn().getResponse().getContentAsString());
    }

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }
}
