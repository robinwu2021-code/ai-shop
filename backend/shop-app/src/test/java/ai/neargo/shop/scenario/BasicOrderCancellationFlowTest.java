package ai.neargo.shop.scenario;

import ai.neargo.shop.common.OtpStore;
import ai.neargo.shop.promotion.dto.ActivityVOs.ActivityDraft;
import ai.neargo.shop.promotion.entity.PmtActivity;
import ai.neargo.shop.promotion.service.ActivityService;
import ai.neargo.shop.support.TestLogin;
import ai.neargo.shop.trade.service.OrderService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** A real unpaid order must return its activity budget as well as stock when cancelled. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:basic-order-cancel;"
        + "MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class BasicOrderCancellationFlowTest {
    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper json;
    @Autowired private OtpStore otp;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ActivityService activities;
    @Autowired private OrderService orders;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void buyerAndPlatformCancellationReturnActivityQuotaAndBudget(boolean platform) throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        String token = TestLogin.consumer(mvc, json, otp, platform ? "13208191002" : "13208191001");
        long now = System.currentTimeMillis();
        String activity = activities.save("M0001", new ActivityDraft(null, "cancel-review-" + UUID.randomUUID(),
                "BASKET", null, PmtActivity.TRIGGER_NONE, null, null,
                PmtActivity.BENEFIT_CUT, 123L, null, null, PmtActivity.ONE_OFF,
                now - 1000, now + 86400_000L, null, 10, 1230L, List.of(), List.of()), "OPS").activityNo();
        try {
            String created = mvc.perform(post("/mp/order").header("Authorization", "Bearer " + token)
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"items\":[{\"goodsNo\":\"G0002\",\"skuNo\":\"SK0003\",\"qty\":1}],"
                            + "\"fulfillment\":\"STORE_PICKUP\",\"pickupNo\":\"PP0001\",\"usePoints\":0,"
                            + "\"activityChoices\":[{\"merchantNo\":\"M0001\",\"activityNo\":\"" + activity + "\"}]}"))
                    .andReturn().getResponse().getContentAsString();
            var response = json.readTree(created);
            assertThat(response.path("code").asInt()).as(created).isZero();
            String payNo = response.path("data").path("payOrderNo").asString();
            assertThat(quota(activity)).as("real checkout reserved activity quota").isEqualTo(1);
            assertThat(budget(activity)).isEqualTo(123L);
            if (platform) {
                orders.cancelByPlatform(payNo, "cancel-review", "OPS");
            } else {
                var cancelled = json.readTree(mvc.perform(post("/mp/order/" + payNo + "/cancel")
                        .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"cancel-review\"}"))
                        .andReturn().getResponse().getContentAsString());
                assertThat(cancelled.path("code").asInt()).as(cancelled.toString()).isZero();
            }
            assertThat(jdbc.queryForObject("select status from ord_order where order_no=?", String.class, payNo))
                    .isEqualTo("CANCELLED");
            assertThat(quota(activity)).as("cancellation returns reserved activity quota").isZero();
            assertThat(budget(activity)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from pmt_apply where order_no=? and deleted=0",
                    Long.class, payNo)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from stl_payment where order_no=?",
                    Long.class, payNo)).isZero();
            orders.closeUnpaid(payNo, "replayed expiry");
            assertThat(quota(activity)).isZero();
        } finally {
            activities.setStatus("M0001", activity, PmtActivity.ENDED);
        }
    }

    private int quota(String activity) {
        return jdbc.queryForObject("select quota_used from pmt_activity where activity_no=?", Integer.class, activity);
    }

    private long budget(String activity) {
        return jdbc.queryForObject("select budget_used_minor from pmt_activity where activity_no=?", Long.class, activity);
    }
}
