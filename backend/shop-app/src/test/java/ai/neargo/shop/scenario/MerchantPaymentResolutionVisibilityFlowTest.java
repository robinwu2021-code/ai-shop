package ai.neargo.shop.scenario;

import ai.neargo.shop.common.OtpStore;
import ai.neargo.shop.support.ShippingMerchantFixture;
import ai.neargo.shop.support.TestLogin;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/** Authorized B and Ops detail must show the same safe financial-conflict facts as the original buyer. */
@SpringBootTest
@ActiveProfiles("test")
class MerchantPaymentResolutionVisibilityFlowTest {
    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private OtpStore otp;

    @ParameterizedTest
    @ValueSource(strings = {"BIZ", "OPS"})
    void authorizedDetailShowsOnlyStatusReasonAndOriginalReference(String surface) throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        String phone = "BIZ".equals(surface) ? "13400854003" : "13400854004";
        ShippingMerchantFixture.Scope scope = ShippingMerchantFixture.open(mvc, json, otp, phone);
        String sub = seed(scope);
        String path = "BIZ".equals(surface) ? "/biz/order/" : "/ops/orders/";
        String token = "BIZ".equals(surface) ? scope.token() : TestLogin.admin(mvc, json);
        mvc.perform(get(path + sub).header("Authorization", "Bearer " + token)
                        .header("X-Store-No", scope.store()))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.paymentResolution.status").value("MANUAL_REQUIRED"))
                .andExpect(jsonPath("$.data.paymentResolution.reason").value("LATE_PAYMENT"))
                .andExpect(jsonPath("$.data.paymentResolution.reference").value("PAY-" + sub))
                .andExpect(jsonPath("$.data.paymentResolution.amountMinor").doesNotExist())
                .andExpect(jsonPath("$.data.paymentResolution.sourceUserNo").doesNotExist());
    }

    private String seed(ShippingMerchantFixture.Scope scope) {
        String sub = "SPR" + UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO ord_order (order_no,user_no,community_no,status,created_at,updated_at) "
                + "VALUES (?,'RESOLUTION-BUYER','C0001','CANCELLED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", "O-" + sub);
        // Deliberately stale child buyer proves lookup uses the authoritative payment order buyer.
        jdbc.update("INSERT INTO ord_sub_order (sub_order_no,order_no,user_no,entity_no,store_no,fulfillment,"
                + "status,created_at,updated_at) VALUES (?,?,'STALE-CHILD-BUYER',?,?,'EXPRESS','CANCELLED',"
                + "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", sub, "O-" + sub, scope.merchant(), scope.store());
        jdbc.update("INSERT INTO ord_payment_resolution (pay_no,payment_no,user_no,amount_minor,currency,"
                + "pay_channel,trade_no,reason,status,created_at,updated_at) VALUES (?,?,'RESOLUTION-BUYER',"
                + "1000,'CNY','WX','ORIGINAL-TRADE','LATE_PAYMENT','MANUAL_REQUIRED',"
                + "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", "O-" + sub, "PAY-" + sub);
        return sub;
    }
}
