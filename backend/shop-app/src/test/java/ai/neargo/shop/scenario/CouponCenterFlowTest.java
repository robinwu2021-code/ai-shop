package ai.neargo.shop.scenario;

import ai.neargo.shop.promotion.dto.CouponVOs.CouponSaveCmd;
import ai.neargo.shop.promotion.entity.PmtCoupon;
import ai.neargo.shop.promotion.service.CouponService;
import ai.neargo.shop.support.TestLogin;
import org.junit.jupiter.api.AfterEach;
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

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 商家建的券，顾客真的领得到、用得上（优惠券全链路梳理 批 1）。
 *
 * <p><b>此前这条链是断的</b>：B 端建券写新模型 {@code pmt_coupon}，C 端领券中心、领券、
 * 我的券、最优券试算只读老模型 {@code mkt_coupon} —— 商家建了券，顾客一张都看不到。
 * 这里从「商家建」一路走到「用它下单真的减了钱」，中间任何一环断了都会红。
 */
@SpringBootTest
@ActiveProfiles("test")
class CouponCenterFlowTest {

    private static final String MERCHANT = "M0001";
    private static int seq = 0;

    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;
    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private CouponService couponService;

    /** 本类建的券用完即停：进行中的「顾客领取」券会出现在别的用例的领券中心里 */
    private final List<String> created = new ArrayList<>();

    @AfterEach
    void endOwnCoupons() {
        for (String no : created) {
            try {
                couponService.setStatus(MERCHANT, no, PmtCoupon.ENDED);
            } catch (RuntimeException ignored) {
                // 已结束的券再结束一次会拒，不影响清理
            }
        }
        created.clear();
    }

    @Test
    @DisplayName("★★★ 商家建「顾客领取」券 → 领券中心看得到 → 领取 → 券包里有 → 最优券推荐它 → 下单真的减")
    void merchantCouponReachesCheckout() throws Exception {
        String title = "领券中心测试" + System.nanoTime() % 100000;
        String couponNo = save(title, PmtCoupon.ISSUE_CENTER);
        String token = login("13000310001");

        JsonNode center = doGet("/mp/coupon", token).get("data");
        JsonNode listed = find(center, "couponNo", couponNo);
        assertThat(listed).as("商家建的券顾客看不到 —— 这正是修之前的样子").isNotNull();
        assertThat(listed.get("type").asString()).isEqualTo("FULL_CUT");
        assertThat(listed.get("faceMinor").asLong()).isEqualTo(500L);
        assertThat(listed.get("received").asBoolean()).isFalse();

        JsonNode got = doPost("/mp/coupon/" + couponNo + "/receive", token, "{}");
        assertThat(got.get("code").asInt()).as(got.toString()).isZero();
        String userCouponNo = got.get("data").get("userCouponNo").asString();

        assertThat(find(doGet("/mp/coupon", token).get("data"), "couponNo", couponNo).get("received").asBoolean())
                .as("领满了要显示「已领」，否则他会一直点").isTrue();
        assertThat(doPost("/mp/coupon/" + couponNo + "/receive", token, "{}").get("code").asInt())
                .as("每人限领 1 张").isNotZero();

        assertThat(find(doGet("/mp/coupon/mine", token).get("data"), "userCouponNo", userCouponNo))
                .as("领到的券进不了券包 = 结账页选不到").isNotNull();

        JsonNode best = doPost("/mp/coupon/best", token,
                "{\"items\":[{\"goodsNo\":\"G0002\",\"skuNo\":\"SK0003\",\"qty\":1}]}").get("data");
        assertThat(find(best.get("usable"), "userCouponNo", userCouponNo)).isNotNull();

        // 用它下单：传的是用户持有的那张（userCouponNo），与 c-app 42546bea 同一口径
        doPost("/mp/cart/add", token, "{\"goodsNo\":\"G0002\",\"skuNo\":\"SK0003\",\"qty\":1}");
        JsonNode preview = doPost("/mp/order/preview", token,
                "{\"fulfillment\":\"STORE_PICKUP\",\"pickupNo\":\"PP0001\",\"couponNo\":\"" + userCouponNo + "\"}");
        assertThat(preview.get("code").asInt()).as(preview.toString()).isZero();
        JsonNode lines = preview.get("data").get("discountLines");
        assertThat(find(lines, "kind", "COUPON")).as("减了却说不出是哪张券").isNotNull();
        assertThat(find(lines, "kind", "COUPON").get("amountMinor").asLong()).isEqualTo(500L);
    }

    @Test
    @DisplayName("★★ 定向券不进领券中心、也不能自己领 —— 它的对象是商家选的")
    void targetedCouponNotSelfServe() throws Exception {
        String couponNo = save("定向测试" + System.nanoTime() % 100000, PmtCoupon.ISSUE_TARGETED);
        String token = login("13000310002");

        assertThat(find(doGet("/mp/coupon", token).get("data"), "couponNo", couponNo)).isNull();
        assertThat(doPost("/mp/coupon/" + couponNo + "/receive", token, "{}").get("code").asInt()).isNotZero();
    }

    @Test
    @DisplayName("★★ 门槛不够时说差多少 —— 券要在活动后的本店金额上判")
    void belowThresholdTellsGap() throws Exception {
        String couponNo = save("门槛测试" + System.nanoTime() % 100000, PmtCoupon.ISSUE_CENTER, 50_000L);
        String token = login("13000310003");
        String userCouponNo = doPost("/mp/coupon/" + couponNo + "/receive", token, "{}")
                .get("data").get("userCouponNo").asString();

        JsonNode best = doPost("/mp/coupon/best", token,
                "{\"items\":[{\"goodsNo\":\"G0002\",\"skuNo\":\"SK0003\",\"qty\":1}]}").get("data");
        JsonNode why = find(best.get("unusable"), "userCouponNo", userCouponNo);
        assertThat(why).isNotNull();
        assertThat(why.get("code").asString()).isEqualTo("BELOW_THRESHOLD");
        assertThat(why.get("gapMinor").asLong()).isPositive();
    }

    // ------------------------------------------------------------------ helpers

    private String save(String title, String issueMode) {
        return save(title, issueMode, 1_000L);
    }

    private String save(String title, String issueMode, long threshold) {
        String no = couponService.save(MERCHANT, new CouponSaveCmd(null, title, PmtCoupon.CASH, 500L, null, null,
                threshold, null, PmtCoupon.SCOPE_ALL, List.of(), null,
                PmtCoupon.RELATIVE, null, null, 7,
                issueMode, PmtCoupon.REDEEM_ORDER, 1, 10, 1, null), "OP").couponNo();
        created.add(no);
        return no;
    }

    private static JsonNode find(JsonNode arr, String field, String value) {
        if (arr == null) {
            return null;
        }
        for (JsonNode n : arr) {
            if (n.get(field) != null && value.equals(n.get(field).asString())) {
                return n;
            }
        }
        return null;
    }

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    private JsonNode doGet(String path, String token) throws Exception {
        return json.readTree(mvc().perform(get(path).header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString());
    }

    private JsonNode doPost(String path, String token, String body) throws Exception {
        return json.readTree(mvc().perform(post(path).header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "cc-" + System.nanoTime())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getContentAsString());
    }

    private String login(String phone) throws Exception {
        return TestLogin.consumer(mvc(), json, otpStore, phone);
    }
}
