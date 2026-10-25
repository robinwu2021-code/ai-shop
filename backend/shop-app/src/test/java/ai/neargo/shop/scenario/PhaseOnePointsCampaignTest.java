package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.neargo.shop.common.OtpStore;
import ai.neargo.shop.promotion.dto.ActivityVOs.ActivityDraft;
import ai.neargo.shop.promotion.dto.ActivityVOs.RuleItem;
import ai.neargo.shop.promotion.entity.PmtActivity;
import ai.neargo.shop.promotion.service.ActivityService;
import ai.neargo.shop.spi.settle.PointsPort;
import ai.neargo.shop.support.TestLogin;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Unsupported rewards cannot be promised or consume campaign quota in cash checkout. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:phase1-points-campaign;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles("test")
class PhaseOnePointsCampaignTest {
    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper json;
    @Autowired private OtpStore otp;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ActivityService activities;
    @MockitoSpyBean private PointsPort points;
    private final List<String> made = new ArrayList<>();

    /*
     * 活动保存/发布还会写版本、商品、人群、预算等关联表。只删主表与规则的话，版本快照里那条
     * 「减 100」继续参与结算，而主表已没了 —— 后面的类（如 PointsDeductFlowTest）的订单凭空少 100、
     * 优惠依据却是空的。按活动号把所有关联表一起删。
     */
    private static final List<String> ACTIVITY_TABLES = List.of(
            "pmt_activity_rule", "pmt_activity_version", "pmt_activity_goods", "pmt_activity_audience",
            "pmt_activity_budget", "pmt_activity_participant", "pmt_activity_reservation", "pmt_period",
            "pmt_enrollment", "pmt_effect_projection", "pmt_coupon_issue", "pmt_activity");

    @AfterEach
    void cleanup() {
        for (String no : made) {
            for (String table : ACTIVITY_TABLES) {
                jdbc.update("delete from " + table + " where activity_no=?", no);
            }
        }
    }

    @Test
    void pointsOnlyCampaignIsNeitherOfferedNorConsumed() throws Exception {
        excludedCampaign(false, "13107120001");
    }

    @Test
    void mixedCampaignIsExcludedAsAWholeRatherThanPromisingMissingReward() throws Exception {
        excludedCampaign(true, "13107120002");
    }

    private void excludedCampaign(boolean mixed, String phone) throws Exception {
        String no = campaign(mixed);
        String token = TestLogin.consumer(mvc(), json, otp, phone);
        JsonNode preview = response(token, "/mp/order/preview", command(null));
        assertThat(preview.path("code").asInt()).isZero();
        assertThat(preview.path("data").path("offers").toString()).doesNotContain(no);
        JsonNode selected = response(token, "/mp/order", command(no));
        assertThat(selected.path("code").asInt()).isEqualTo(40035);
        JsonNode created = response(token, "/mp/order", command(null));
        assertThat(created.path("code").asInt()).isZero();
        String payNo = created.path("data").path("payOrderNo").asString();
        assertThat(jdbc.queryForObject("select quota_used from pmt_activity where activity_no=?",
                Integer.class, no)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from pmt_apply where order_no=?",
                Integer.class, payNo)).isZero();
        mvc().perform(post("/mp/order/" + payNo + "/pay").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(0));
        mvc().perform(post("/pay/callback/stub").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outTradeNo\":\"" + payNo
                                + "\",\"transactionId\":\"PC-" + payNo + "\",\"sign\":\"stub-secret\"}"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select status from ord_order where order_no=?", String.class, payNo))
                .isEqualTo("PAID");
        verifyNoInteractions(points);
    }

    private String campaign(boolean mixed) {
        List<RuleItem> rules = new ArrayList<>();
        rules.add(new RuleItem("CONDITION", "QTY", null, 1, null, null, null));
        rules.add(new RuleItem("BENEFIT", "POINTS", null, 50, null, null, null));
        if (mixed) {
            rules.add(new RuleItem("BENEFIT", "CUT", 100L, null, null, null, null));
        }
        long now = System.currentTimeMillis();
        String no = activities.save("M0001", new ActivityDraft(null, "买一件送50积分", null, null,
                PmtActivity.TRIGGER_COMBO, null, null, PmtActivity.BENEFIT_COMBO, null, null, null,
                PmtActivity.ONE_OFF, now - 60_000, now + 86_400_000, null, 100, null, List.of(), List.of(),
                null, null, null, null, null, null, null, rules), "TEST").activityNo();
        made.add(no);
        return no;
    }

    private JsonNode response(String token, String route, String command) throws Exception {
        return json.readTree(mvc().perform(post(route).header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(command))
                .andReturn().getResponse().getContentAsString());
    }

    private String command(String activity) {
        String choices = activity == null ? "" : ",\"activityChoices\":[{\"merchantNo\":\"M0001\","
                + "\"activityNo\":\"" + activity + "\"}]";
        return "{\"items\":[{\"goodsNo\":\"G0001\",\"skuNo\":\"SK0001\",\"qty\":1}],"
                + "\"fulfillment\":\"STORE_PICKUP\",\"pickupNo\":\"PP0001\"" + choices + "}";
    }

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }
}
