package ai.neargo.shop.scenario;

import ai.neargo.shop.support.TestLogin;
import ai.neargo.shop.marketing.coupon.entity.MktCoupon;
import ai.neargo.shop.marketing.coupon.mapper.CouponMappers.CouponMapper;
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

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 「最优券试算」与「下单实际算价」必须给出同一个数。
 *
 * <p><b>这两条是独立的代码路径</b>：`CouponService.best()` 只被 C 端的
 * `/mp/coupon/best` 调用，而下单走 `CouponPort.allocate()` ——
 * 服务端下单时不会自己去选最优券（用户可能手动改选，这本身是对的）。
 *
 * <p>两条路径算同一件事，就会分叉。<b>已经分叉过一次</b>：
 * `best()` 只看 {@code faceMinor}，而折扣券的面额是 0 ——
 * 于是<b>「最优券」永远不推荐折扣券</b>，用户手动选能用、自动选选不出来，
 * 而两边的代码单独看都说得通。那次的修法是把折扣算术收进
 * {@code MktCoupon.discountFor} 一处。
 *
 * <p>收进一处只是让当下正确，<b>挡不住下一次分叉</b> ——
 * 这条测试才是：对同一组商品与券，断言试算报的数字与下单实扣的一致。
 */
@SpringBootTest
@ActiveProfiles("test")
class CouponBestConsistencyTest {

    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;


    @Autowired
    private CouponMapper couponMapper;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                        .springSecurity())
                .build();
    }

    @Test
    @DisplayName("★★★ 满减券：试算说减多少，下单就减多少")
    void fullCutBestMatchesOrder() throws Exception {
        assertConsistent("13800138001", fullCutCoupon("一致性满减", 1500L, 5000L));
    }

    @Test
    @DisplayName("★★★ 折扣券：试算说减多少，下单就减多少 —— 这一类曾经在试算侧恒为 0")
    void discountBestMatchesOrder() throws Exception {
        assertConsistent("13800138002", discountCoupon("一致性八五折", 8500, 3000L));
    }

    /**
     * 同一组商品、同一张券：`/mp/coupon/best` 报的 {@code discountMinor}
     * 必须等于下单后订单上的 {@code discountMinor}。
     */
    private void assertConsistent(String phone, String couponNo) throws Exception {
        String token = login(phone);
        String userCouponNo = receive(token, couponNo);
        addToCart(token, "G0001", "SK0001", 2);   // 4980 × 2 = 9960

        String bestBody = mvc().perform(post("/mp/coupon/best")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"goodsNo\":\"G0001\",\"skuNo\":\"SK0001\",\"qty\":2}]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        JsonNode best = json.readTree(bestBody).get("data");

        assertThat(best.get("bestUserCouponNo").asString())
                .as("这张券可用，最优券就该选中它 —— 选不出来的典型原因是试算侧算出 0")
                .isEqualTo(userCouponNo);
        long predicted = best.get("discountMinor").asLong();
        assertThat(predicted).as("试算报 0 等于「没有可用券」").isGreaterThan(0);

        String orderBody = mvc().perform(post("/mp/order")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fulfillment\":\"STORE_PICKUP\",\"pickupNo\":\"PP0001\","
                                + "\"couponNo\":\"" + userCouponNo + "\",\"idempotencyKey\":\""
                                + java.util.UUID.randomUUID() + "\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        long actual = json.readTree(orderBody).get("data").get("amount").get("discountMinor").asLong();

        assertThat(actual)
                .as("试算与实扣对不上 = 用户在结算页看到一个数、付款时是另一个数")
                .isEqualTo(predicted);
    }

    // ---------------------------------------------------------------- 助手

    private String fullCutCoupon(String title, long faceMinor, long thresholdMinor) {
        MktCoupon c = base(title, MktCoupon.FULL_CUT);
        c.setFaceMinor(faceMinor);
        c.setThresholdMinor(thresholdMinor);
        couponMapper.insert(c);
        return c.getCouponNo();
    }

    /** @param rate 折扣**万分比**：8500 = 八五折（与 ops-web 和 CampaignType 同口径） */
    private String discountCoupon(String title, int rate, long maxDiscountMinor) {
        MktCoupon c = base(title, MktCoupon.DISCOUNT);
        c.setFaceMinor(0L);
        c.setDiscountRate(rate);
        c.setMaxDiscountMinor(maxDiscountMinor);
        c.setThresholdMinor(0L);
        couponMapper.insert(c);
        return c.getCouponNo();
    }

    private MktCoupon base(String title, String type) {
        MktCoupon c = new MktCoupon();
        c.setCouponNo("CP-" + java.util.UUID.randomUUID().toString().substring(0, 8));
        c.setTitle(title);
        c.setType(type);
        c.setFunder(MktCoupon.BY_PLATFORM);
        c.setTotalCount(100);
        c.setReceivedCount(0);
        c.setPerUserLimit(1);
        c.setBudgetMinor(0L);
        c.setStartAt(System.currentTimeMillis() - 3600_000L);
        c.setEndAt(System.currentTimeMillis() + 86_400_000L);
        c.setStatus(MktCoupon.ACTIVE);
        c.setCreatedAt(LocalDateTime.now());
        c.setUpdatedAt(LocalDateTime.now());
        return c;
    }

    private String receive(String token, String couponNo) throws Exception {
        String body = mvc().perform(post("/mp/coupon/" + couponNo + "/receive")
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("userCouponNo").asString();
    }

    private void addToCart(String token, String goodsNo, String skuNo, int qty) throws Exception {
        mvc().perform(post("/mp/cart/add").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"goodsNo\":\"" + goodsNo + "\",\"skuNo\":\"" + skuNo + "\",\"qty\":"
                        + qty + "}"));
    }

    private String login(String phone) throws Exception {
        return TestLogin.consumer(mvc(), json, otpStore, phone);
    }
}
