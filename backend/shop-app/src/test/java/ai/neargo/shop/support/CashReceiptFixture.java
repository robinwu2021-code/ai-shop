package ai.neargo.shop.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ai.neargo.shop.pay.channel.TestPayGateway;
import ai.neargo.shop.trade.order.refund.service.BasicRefundExecutionService;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

/** Real cash receipts for older scenarios; merchant routing and all business services remain unchanged. */
public final class CashReceiptFixture {
    private final WebApplicationContext context;
    private final JdbcTemplate jdbc;
    private final MockMvc mvc;
    private final ObjectMapper json;
    private final TestPayGateway gateway;

    public CashReceiptFixture(WebApplicationContext context) {
        this.context = context;
        jdbc = context.getBean(JdbcTemplate.class);
        json = context.getBean(ObjectMapper.class);
        gateway = context.getBean(TestPayGateway.class);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    public void pay(String token, String parent) throws Exception {
        String response = mvc.perform(post("/mp/order/" + parent + "/pay")
                .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(response).path("code").asInt()).as(response).isZero();
        if ("PAID".equals(jdbc.queryForObject("select status from ord_order where order_no=?", String.class, parent))) {
            return;
        }
        String out = jdbc.queryForObject("select out_trade_no from stl_payment where order_no=?"
                + " and direction='PAY' and status='PENDING'", String.class, parent);
        assertThat(gateway.markPaid(out)).isTrue();
        String ack = mvc.perform(post("/pay/callback/stub").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("outTradeNo", out,
                        "transactionId", gateway.query(out).tradeNo(),
                        "sign", "stub-secret")))).andReturn().getResponse().getContentAsString();
        assertThat(ack).isEqualTo("SUCCESS");
        assertThat(jdbc.queryForObject("select status from stl_payment where out_trade_no=?",
                String.class, out)).isEqualTo("SUCCESS");
    }

    public void confirmRefund(String afterSale) {
        String execution = jdbc.queryForObject("select execution_no from ord_refund_execution where after_sale_no=?",
                String.class, afterSale);
        String out = jdbc.queryForObject("select out_trade_no from stl_payment where refund_execution_no=?",
                String.class, execution);
        assertThat(gateway.queryRefund(out).paid()).isTrue();
        context.getBean(BasicRefundExecutionService.class).recover(execution);
        assertThat(jdbc.queryForObject("select status from stl_payment where out_trade_no=?",
                String.class, out)).isEqualTo("SUCCESS");
        assertThat(jdbc.queryForObject("select status from ord_after_sale where after_sale_no=?",
                String.class, afterSale)).isEqualTo("REFUNDED");
    }
}
