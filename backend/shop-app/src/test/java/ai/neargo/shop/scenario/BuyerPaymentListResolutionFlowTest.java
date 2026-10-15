package ai.neargo.shop.scenario;

import ai.neargo.shop.support.TestLogin;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** Buyer list projects safe original-payment conflicts without trusting child or foreign receipt ownership. */
@SpringBootTest
@ActiveProfiles("test")
class BuyerPaymentListResolutionFlowTest {
    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;

    @Test
    void buyerListShowsOwnConflictAndCannotExposeForeignResolution() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        String token = TestLogin.consumerByWechat(mvc, json, "phase1-review-list");
        String buyer = read(mvc, token, "/mp/user/profile").path("userNo").asString();
        String own = seed(buyer, buyer);
        String wrongReceiptOwner = seed(buyer, "FOREIGN-BUYER");
        String foreign = seed("FOREIGN-BUYER", "FOREIGN-BUYER");
        JsonNode records = read(mvc, token, "/mp/order?size=100").path("records");
        JsonNode found = null;
        for (JsonNode row : records) {
            String no = row.path("orderNo").asString();
            assertThat(no).isNotEqualTo(foreign);
            if (own.equals(no)) { found = row; }
            if (wrongReceiptOwner.equals(no)) { assertThat(row.path("paymentResolution").isMissingNode()
                    || row.path("paymentResolution").isNull()).isTrue(); }
        }
        assertThat(found).isNotNull();
        assertThat(found.path("paymentResolution").path("status").asString()).isEqualTo("MANUAL_REQUIRED");
        assertThat(found.path("paymentResolution").path("reference").asString()).isEqualTo("PAY-" + own);
        assertThat(found.path("paymentResolution").has("amountMinor")).isFalse();
        assertThat(found.path("paymentResolution").has("sourceUserNo")).isFalse();
    }

    @Test
    void historicalChildWithoutStoreDoesNotBreakBuyerList() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        String token = TestLogin.consumerByWechat(mvc, json, "phase1-review-list-history");
        String buyer = read(mvc, token, "/mp/user/profile").path("userNo").asString();
        String sub = seed(buyer, buyer);
        jdbc.update("UPDATE ord_sub_order SET store_no=NULL WHERE sub_order_no=?", sub);
        JsonNode records = read(mvc, token, "/mp/order?size=100").path("records");
        assertThat(records.size()).isEqualTo(1);
        assertThat(records.get(0).path("orderNo").asString()).isEqualTo(sub);
    }

    private JsonNode read(MockMvc mvc, String token, String path) throws Exception {
        JsonNode response = json.readTree(mvc.perform(get(path).header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString());
        assertThat(response.path("code").asInt()).isZero();
        return response.path("data");
    }

    private String seed(String buyer, String resolutionOwner) {
        String sub = "LST" + UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO ord_order (order_no,user_no,status,pay_amount,created_at,updated_at) "
                + "VALUES (?,?,'WAIT_PAY',1000,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", "O-" + sub, buyer);
        jdbc.update("INSERT INTO ord_sub_order (sub_order_no,order_no,user_no,entity_no,store_no,fulfillment,status,"
                + "pay_amount,created_at,updated_at) VALUES (?,?,?,'LIST-MCH','LIST-STORE','EXPRESS','WAIT_PAY',1000,"
                + "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", sub, "O-" + sub, buyer);
        jdbc.update("INSERT INTO ord_payment_resolution (pay_no,payment_no,user_no,amount_minor,currency,"
                + "pay_channel,trade_no,reason,status,created_at,updated_at) VALUES (?,?,?,1000,'CNY',"
                + "'WX','ORIGINAL-TRADE','AMOUNT_MISMATCH','MANUAL_REQUIRED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                "O-" + sub, "PAY-" + sub, resolutionOwner);
        return sub;
    }
}
