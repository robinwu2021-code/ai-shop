package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ai.neargo.shop.spi.pay.ChannelCallbackVerifier;
import ai.neargo.shop.spi.pay.PayQueryPort;
import ai.neargo.shop.support.port.FakePayQueryPort;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** Real callback and financial services reject receipts that disagree with the immutable refund instruction. */
@SpringBootTest(properties = {
        "spring.datasource.url=${order.phase1.refund.callback.test.url:jdbc:h2:mem:phase1-refund-callback;"
                + "MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1}"
})
@ActiveProfiles({"test", "api"})
@Import(StrictRefundCallbackFlowTest.ExternalChannel.class)
class StrictRefundCallbackFlowTest {
    private static final String CHANNEL = "REFUND_TEST";
    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private FakePayQueryPort query;

    @AfterEach
    void resetChannel() {
        query.reset();
    }

    @Test
    void wrongAmountCannotConfirmRefund() throws Exception {
        String no = pending(CHANNEL);
        assertThat(callback(no, 999, "CNY", "provider")).isEqualTo("FAIL");
        assertPending(no);
    }

    @Test
    void wrongCurrencyCannotConfirmRefund() throws Exception {
        String no = pending(CHANNEL);
        assertThat(callback(no, 1000, "USD", "provider")).isEqualTo("FAIL");
        assertPending(no);
    }

    @Test
    void anotherChannelCannotConfirmRefund() throws Exception {
        String no = pending("STUB");
        assertThat(callback(no, 1000, "CNY", "provider")).isEqualTo("FAIL");
        assertPending(no);
    }

    @Test
    void blankProviderCannotConfirmRefund() throws Exception {
        String no = pending(CHANNEL);
        assertThat(callback(no, 1000, "CNY", " ")).isEqualTo("FAIL");
        assertPending(no);
    }

    @Test
    void confirmedRefundRejectsDifferentProviderReplay() throws Exception {
        String no = pending(CHANNEL);
        assertThat(callback(no, 1000, "CNY", "first-provider")).isEqualTo("SUCCESS");
        Long first = jdbc.queryForObject("select succeeded_at from stl_payment where out_trade_no=?", Long.class, no);
        assertThat(first).isPositive();
        assertThat(callback(no, 1000, "CNY", "different-provider")).isEqualTo("FAIL");
        assertThat(jdbc.queryForObject("select trade_no from stl_payment where out_trade_no=?", String.class, no))
                .isEqualTo("first-provider");
        assertThat(jdbc.queryForObject("select succeeded_at from stl_payment where out_trade_no=?", Long.class, no))
                .isEqualTo(first);
        assertThat(callback(no, 1000, "CNY", "first-provider")).isEqualTo("SUCCESS");
    }

    private String pending(String channel) {
        String no = "RFC-" + UUID.randomUUID();
        jdbc.update("insert into stl_payment(payment_no,out_trade_no,order_no,direction,status,pay_channel,"
                        + "user_no,amount_minor,currency,tenant_no,version,deleted,created_at,updated_at)"
                        + " values(?,?,?,'REFUND','PENDING',?,'U-REFUND',1000,'CNY','MAIN',0,0,now(),now())",
                "P-" + no, no, "O-" + no, channel);
        return no;
    }

    private void assertPending(String no) {
        assertThat(jdbc.queryForObject("select status from stl_payment where out_trade_no=?", String.class, no))
                .isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select succeeded_at from stl_payment where out_trade_no=?", Long.class, no))
                .isNull();
    }

    private String callback(String no, long amount, String currency, String provider) throws Exception {
        query.answer(new PayQueryPort.Result(true, true, true, amount, provider, currency));
        // 走真实的安全链：验签在渠道回调守卫里（开发计划 M4）
        return MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build()
                .perform(post("/pay/callback/" + CHANNEL + "/refund")
                        .contentType(MediaType.APPLICATION_JSON).content("verified:" + no))
                .andReturn().getResponse().getContentAsString();
    }

    @TestConfiguration
    static class ExternalChannel {
        @Bean
        ChannelCallbackVerifier refundVerifier() {
            return verifier(CHANNEL);
        }

        @Bean
        ChannelCallbackVerifier testRefundVerifier() {
            return verifier("TEST");
        }

        private ChannelCallbackVerifier verifier(String channel) {
            return new ChannelCallbackVerifier() {
                @Override
                public String payChannel() {
                    return channel;
                }

                @Override
                public Map<String, Object> verify(Map<String, String> headers, String rawBody) {
                    return rawBody.startsWith("verified:")
                            ? Map.of("out_refund_no", rawBody.substring(9)) : null;
                }

                @Override
                public String ackOk() {
                    return "SUCCESS";
                }

                @Override
                public String ackFail() {
                    return "FAIL";
                }
            };
        }
    }
}
