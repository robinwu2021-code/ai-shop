package ai.neargo.shop.scenario;

import ai.neargo.shop.promotion.dto.ActivityVOs.ActivityDraft;
import ai.neargo.shop.promotion.dto.CouponVOs.CouponSaveCmd;
import ai.neargo.shop.promotion.entity.PmtActivity;
import ai.neargo.shop.promotion.entity.PmtCoupon;
import ai.neargo.shop.promotion.service.ActivityService;
import ai.neargo.shop.promotion.service.CouponService;
import ai.neargo.shop.support.TestLogin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
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
 * 下单页：顾客可以选参加哪个活动、或不参加；系统默认给最省的组合（优惠券全链路梳理 批 2）。
 *
 * <p>场景：金龙鱼 ¥69.80（M0001）。本店两个活动 ——「立减 5」（无门槛）与「满 50 减 8」；
 * 顾客手里一张本店券「满 65 减 10」。
 * <ul>
 *   <li>参加满减 8 → 剩 61.80，够不到 65 的券门槛 → 共减 8</li>
 *   <li>参加立减 5 → 剩 64.80，还是够不到 → 共减 5</li>
 *   <li><b>不参加活动、只用券</b> → 共减 10 ← 最省</li>
 * </ul>
 * 「先挑最优活动、再挑券」的贪心算法会停在 8 —— 这正是要顾客自己去点「不参与」的那种情况，
 * 系统应该先替他想到。
 */
@SpringBootTest
@ActiveProfiles("test")
class CheckoutOffersFlowTest {

    private static final String MERCHANT = "M0001";
    private static final String ITEMS = "\"items\":[{\"goodsNo\":\"G0002\",\"skuNo\":\"SK0003\",\"qty\":1}]";

    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;
    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private ActivityService activityService;
    @Autowired
    private CouponService couponService;

    private String cut5;
    private String cut8;
    private String couponNo;

    @BeforeEach
    void offers() {
        long now = System.currentTimeMillis();
        cut5 = activityService.save(MERCHANT, new ActivityDraft(null, "立减5·" + now % 10000, "BASKET", null,
                PmtActivity.TRIGGER_NONE, null, null, PmtActivity.BENEFIT_CUT, 500L, null, null,
                PmtActivity.ONE_OFF, now - 1000, now + 86_400_000L, null, null, null, List.of(), List.of()),
                "OP").activityNo();
        cut8 = activityService.save(MERCHANT, new ActivityDraft(null, "满50减8·" + now % 10000, "BASKET", null,
                PmtActivity.TRIGGER_AMOUNT, 5_000L, null, PmtActivity.BENEFIT_CUT, 800L, null, null,
                PmtActivity.ONE_OFF, now - 1000, now + 86_400_000L, null, null, null, List.of(), List.of()),
                "OP").activityNo();
        couponNo = couponService.save(MERCHANT, new CouponSaveCmd(null, "满65减10·" + now % 10000,
                PmtCoupon.CASH, 1_000L, null, null, 6_500L, null, PmtCoupon.SCOPE_ALL, List.of(), null,
                PmtCoupon.RELATIVE, null, null, 7, PmtCoupon.ISSUE_CENTER, PmtCoupon.REDEEM_ORDER, 1,
                10, 1, null), "OP").couponNo();
    }

    /** 共享种子 M0001 上的活动与券用完即停：进行中的会减掉别的用例的钱 */
    @AfterEach
    void endOffers() {
        for (String no : new String[]{cut5, cut8}) {
            try {
                activityService.setStatus(MERCHANT, no, PmtActivity.ENDED);
            } catch (RuntimeException ignored) {
                // 已结束
            }
        }
        try {
            couponService.setStatus(MERCHANT, couponNo, PmtCoupon.ENDED);
        } catch (RuntimeException ignored) {
            // 已结束
        }
    }

    @Test
    @DisplayName("★★★ 预览列出本店命中的全部活动，默认选最优的那个")
    void optionsListedDefaultBest() throws Exception {
        String token = login("13000320001");
        JsonNode p = preview(token, "");
        JsonNode m = merchant(p);
        assertThat(option(m, cut5)).as("命中的活动没列出来，顾客没法换").isNotNull();
        assertThat(option(m, cut8)).isNotNull();
        assertThat(option(m, cut8).get("amountMinor").asLong()).isEqualTo(800L);
        assertThat(m.get("chosen").asString()).as("没人选时按最优").isEqualTo(cut8);
    }

    @Test
    @DisplayName("★★★ 顾客可以换一个活动，也可以不参加")
    void customerChooses() throws Exception {
        String token = login("13000320002");
        JsonNode five = preview(token, choice(cut5));
        assertThat(merchant(five).get("chosen").asString()).isEqualTo(cut5);
        assertThat(five.get("amount").get("discountMinor").asLong()).isGreaterThanOrEqualTo(500L);

        JsonNode none = preview(token, choice("NONE"));
        assertThat(merchant(none).get("chosen").asString()).isEqualTo("NONE");
        assertThat(activityLines(none)).as("选了不参加还在减 —— 他的选择没被尊重").isZero();
    }

    @Test
    @DisplayName("★★ 选的活动此刻不成立 → 明说（40035），不偷偷换成别的")
    void unavailableChoiceRejected() throws Exception {
        String token = login("13000320003");
        JsonNode r = call("/mp/order/preview", token,
                "{\"fulfillment\":\"STORE_PICKUP\",\"pickupNo\":\"PP0001\"," + ITEMS + "," + choice("PT-NOT-EXIST") + "}");
        assertThat(r.get("code").asInt()).isEqualTo(40035);
    }

    @Test
    @DisplayName("★★★ 最省组合：参加满减会够不到券门槛时，建议「不参加活动、只用券」")
    void suggestsBestCombination() throws Exception {
        String token = login("13000320004");
        String userCouponNo = call("/mp/coupon/" + couponNo + "/receive", token, "{}")
                .get("data").get("userCouponNo").asString();

        JsonNode offers = preview(token, "").get("offers");
        assertThat(offers.get("suggestedCouponNo").asString()).isEqualTo(userCouponNo);
        JsonNode sug = null;
        for (JsonNode c : offers.get("suggestedChoices")) {
            if (MERCHANT.equals(c.get("merchantNo").asString())) {
                sug = c;
            }
        }
        assertThat(sug).isNotNull();
        assertThat(sug.get("activityNo").asString())
                .as("贪心算法会停在「满减 8」—— 那比「只用券 10」少省 2 块").isEqualTo("NONE");
        assertThat(offers.get("suggestedDiscountMinor").asLong()).isGreaterThanOrEqualTo(1_000L);

        // 照建议下单：金额就是建议里说的那个
        JsonNode followed = preview(token, choice("NONE") + ",\"couponNo\":\"" + userCouponNo + "\"");
        assertThat(followed.get("amount").get("discountMinor").asLong())
                .isEqualTo(offers.get("suggestedDiscountMinor").asLong());
    }

    @Test
    @DisplayName("★★ 商品页带出本店活动标签 —— 满减此前只在下单页出现，逛的时候不知道要凑单")
    void goodsPageShowsActivityTags() throws Exception {
        String token = login("13000320005");
        JsonNode g = json.readTree(mvc().perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/mp/goods/G0002")
                                .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString()).get("data");
        JsonNode tags = g.get("activityTags");
        assertThat(tags).as("详情没下发活动标签").isNotNull();
        JsonNode t8 = null;
        for (JsonNode t : tags) {
            if (cut8.equals(t.get("activityNo").asString())) {
                t8 = t;
            }
        }
        assertThat(t8).isNotNull();
        assertThat(t8.get("thresholdMinor").asLong()).isEqualTo(5_000L);
        assertThat(t8.get("amountMinor").asLong()).isEqualTo(800L);
    }

    // ------------------------------------------------------------------ helpers

    private String choice(String activityNo) {
        return "\"activityChoices\":[{\"merchantNo\":\"" + MERCHANT + "\",\"activityNo\":\"" + activityNo + "\"}]";
    }

    private JsonNode preview(String token, String extra) throws Exception {
        JsonNode r = call("/mp/order/preview", token, "{\"fulfillment\":\"STORE_PICKUP\",\"pickupNo\":\"PP0001\","
                + ITEMS + (extra.isEmpty() ? "" : "," + extra) + "}");
        assertThat(r.get("code").asInt()).as(r.toString()).isZero();
        return r.get("data");
    }

    private static JsonNode merchant(JsonNode preview) {
        for (JsonNode m : preview.get("offers").get("merchants")) {
            if (MERCHANT.equals(m.get("merchantNo").asString())) {
                return m;
            }
        }
        throw new AssertionError("预览里没有本店的活动选项：" + preview.get("offers"));
    }

    private static JsonNode option(JsonNode merchant, String activityNo) {
        for (JsonNode o : merchant.get("options")) {
            if (activityNo.equals(o.get("activityNo").asString())) {
                return o;
            }
        }
        return null;
    }

    private static long activityLines(JsonNode preview) {
        long n = 0;
        for (JsonNode d : preview.get("discountLines")) {
            if ("ACTIVITY".equals(d.get("kind").asString())) {
                n += d.get("amountMinor").asLong();
            }
        }
        return n;
    }

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    private JsonNode call(String path, String token, String body) throws Exception {
        return json.readTree(mvc().perform(post(path).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getContentAsString());
    }

    private String login(String phone) throws Exception {
        return TestLogin.consumer(mvc(), json, otpStore, phone);
    }
}
