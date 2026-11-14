package ai.neargo.shop.scenario;

import ai.neargo.shop.promotion.dto.ActivityVOs.ActivityDraft;
import ai.neargo.shop.promotion.entity.PmtActivity;
import ai.neargo.shop.promotion.service.ActivityService;
import ai.neargo.shop.support.TestLogin;
import org.junit.jupiter.api.AfterEach;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 下单链路真的会扣买赠活动的限量。
 *
 * <p>{@code ActivityItemQuotaCommitTest} 守的是营销侧回执接口本身；这里守的是
 * {@code OrderServiceImpl} 下单时<b>确实调了它</b> —— 接口写好了而下单不调，限量照样永远是 0。
 * 走真 HTTP 下单。
 *
 * <p>号段 1300309xxxx 本类独占。
 */
@SpringBootTest
@ActiveProfiles("test")
class ActivityQuotaOrderFlowTest {

    private static final String GOODS = "G0001";
    private static final String SKU = "SK0001";
    private static int seq;

    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper json;
    @Autowired private ai.neargo.shop.common.OtpStore otpStore;
    @Autowired private ActivityService activityService;
    @Autowired private JdbcTemplate jdbc;

    private String activityNo;

    @AfterEach
    void dropActivity() {
        if (activityNo != null) {
            jdbc.update("delete from pmt_activity where activity_no=?", activityNo);
        }
    }

    @Test
    @DisplayName("★★★ 新模型「买二送一」限量 5 份：下一单送出赠品，quota_used 从 0 变 1")
    void giftOrderConsumesActivityQuota() throws Exception {
        long now = System.currentTimeMillis();
        activityNo = activityService.save("M0001", new ActivityDraft(null, "下单扣量·买赠", "BASKET", null,
                PmtActivity.TRIGGER_QTY, null, 2, PmtActivity.BENEFIT_GIFT, null, 1, GOODS,
                PmtActivity.ONE_OFF, now - 1000, now + 86_400_000L, null, 5, null, List.of(), List.of(GOODS)),
                "OP").activityNo();

        String token = TestLogin.consumer(mvc(), json, otpStore, "1300309" + String.format("%04d", ++seq));
        JsonNode r = json.readTree(mvc().perform(post("/mp/order")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "aq-" + System.nanoTime())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fulfillment\":\"STORE_PICKUP\",\"pickupNo\":\"PP0001\",\"items\":[{\"goodsNo\":\""
                                + GOODS + "\",\"skuNo\":\"" + SKU + "\",\"qty\":2}]}"))
                .andReturn().getResponse().getContentAsString());
        assertThat(r.get("code").asInt()).as("下单失败：" + r).isZero();
        String orderNo = r.get("data").get("orderNo").asString();

        assertThat(jdbc.queryForObject("select count(*) from ord_item where order_no=? and is_gift=1",
                Integer.class, orderNo)).as("前提：这一单确实送了赠品").isPositive();
        assertThat(jdbc.queryForObject("select quota_used from pmt_activity where activity_no=?",
                Integer.class, activityNo)).as("★ 送出了赠品，买赠活动的限量却没被扣").isEqualTo(1);
    }

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }
}
