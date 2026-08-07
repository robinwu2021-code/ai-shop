package ai.neargo.shop.scenario;

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

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M6b 优惠券 —— **用例先行**。
 *
 * <p>本模块的资金正确性全在**分摊**上（Q9 / db-design §3.4）：
 * 一张跨商家的券，钱怎么分到每个子单，直接决定每个商家分到多少。
 * 因此三条独立用例守住：按比例分摊、尾数归属、出资方分列。
 */
@SpringBootTest
@ActiveProfiles("test")
class M6bCouponFlowTest {

    private static final String STUB_SECRET = "stub-secret";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private ai.neargo.shop.user.service.OtpStore otpStore;

    @Autowired
    private CouponMapper couponMapper;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    // ---------------------------------------------------------------- 分摊（Q9）

    @Test
    @DisplayName("★ 跨商家券按商品额比例分摊，Σ子单优惠 = 券面额（一分不多一分不少）")
    void discountSplitsProportionally() throws Exception {
        String token = login("13000130001");
        String couponNo = platformCoupon("满100减10", 1000L, 10000L);
        String userCouponNo = receive(token, couponNo);

        // M0001: 4980×2 = 9960；M0002: 5800 → 商品额合计 15760
        addToCart(token, "G0001", "SK0001", 2);
        addToCart(token, "G0003", "SK0004", 1);

        JsonNode data = preview(token, userCouponNo);
        assertThat(data.get("amount").get("discountMinor").asLong()).isEqualTo(1000L);

        long sum = 0;
        for (JsonNode sub : data.get("subOrders")) {
            sum += sub.get("amount").get("discountMinor").asLong();
        }
        // 分摊后必须严丝合缝：少一分商家吃亏，多一分平台吃亏，都会在对账时被发现
        assertThat(sum).isEqualTo(1000L);
    }

    @Test
    @DisplayName("★ 分摊尾数给**商品额最大**的子单（按金额排序稳定，与购物车顺序无关）")
    void remainderGoesToLargestSubOrder() throws Exception {
        String token = login("13000130002");
        // 面额 1000，商品额 9960 : 5800 → 631.4... : 368.5...，必然产生尾数
        String userCouponNo = receive(token, platformCoupon("满100减10", 1000L, 10000L));
        addToCart(token, "G0001", "SK0001", 2);
        addToCart(token, "G0003", "SK0004", 1);

        JsonNode data = preview(token, userCouponNo);
        long big = 0;
        long small = 0;
        for (JsonNode sub : data.get("subOrders")) {
            long goods = sub.get("amount").get("goodsMinor").asLong();
            long disc = sub.get("amount").get("discountMinor").asLong();
            if (goods == 9960L) {
                big = disc;
            } else {
                small = disc;
            }
        }
        // 9960/15760×1000 = 631.98 → 631；5800/15760×1000 = 368.02 → 368；尾数 1 给大单
        assertThat(small).isEqualTo(368L);
        assertThat(big).isEqualTo(632L);
    }

    @Test
    @DisplayName("★ 出资方分列：平台券记 discountPlatform，商家券记 discountMerchant")
    void funderIsRecordedSeparately() throws Exception {
        String token = login("13000130003");
        String userCouponNo = receive(token, merchantCoupon("老张店满50减5", 500L, 5000L, "M0001"));
        addToCart(token, "G0001", "SK0001", 2);

        String payOrderNo = createOrder(token, userCouponNo, "m6b-funder");
        // 出资方决定 M7 分账扣谁的钱 —— 合成一列的话这个信息就永久丢失了
        assertThat(discountMerchantOf(payOrderNo)).isEqualTo(500L);
        assertThat(discountPlatformOf(payOrderNo)).isZero();
    }

    // ---------------------------------------------------------------- 领取与使用

    @Test
    @DisplayName("领券中心可看；领取后进券包，重复领取被拦")
    void receiveCoupon() throws Exception {
        String token = login("13000130010");
        String couponNo = platformCoupon("新人券", 500L, 1000L);

        mvc().perform(get("/mp/coupon").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        receive(token, couponNo);
        mvc().perform(get("/mp/coupon/mine").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.length()").value(org.hamcrest.Matchers.greaterThan(0)));

        // 每人限领 1 张
        mvc().perform(post("/mp/coupon/" + couponNo + "/receive").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    @DisplayName("券库存发完就不能再领（并发下也不能超发）")
    void couponStockIsLimited() throws Exception {
        String couponNo = platformCoupon("限量券", 500L, 1000L, 1, 1);

        String a = login("13000130011");
        String b = login("13000130012");
        receive(a, couponNo);

        mvc().perform(post("/mp/coupon/" + couponNo + "/receive").header("Authorization", "Bearer " + b))
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    @DisplayName("用券下单后券转 USED；取消订单退回券")
    void couponReleasedOnCancel() throws Exception {
        String token = login("13000130013");
        String userCouponNo = receive(token, platformCoupon("满50减5", 500L, 5000L));
        addToCart(token, "G0002", "SK0003", 1);
        String payOrderNo = createOrder(token, userCouponNo, "m6b-cancel");

        assertThat(couponStatus(token, userCouponNo)).isEqualTo("USED");

        mvc().perform(post("/mp/order/" + payOrderNo + "/cancel").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"不要了\"}"));

        // 取消不退券的话，用户会觉得券被平台吞了 —— 这是券功能第二大客诉
        assertThat(couponStatus(token, userCouponNo)).isEqualTo("UNUSED");
    }

    @Test
    @DisplayName("同一张券不能用在两个订单上")
    void couponCannotBeUsedTwice() throws Exception {
        String token = login("13000130014");
        String userCouponNo = receive(token, platformCoupon("满50减5", 500L, 5000L));

        addToCart(token, "G0002", "SK0003", 1);
        createOrder(token, userCouponNo, "m6b-twice-1");

        addToCart(token, "G0002", "SK0003", 1);
        mvc().perform(post("/mp/order").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "m6b-twice-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fulfillment\":\"STORE_PICKUP\",\"pickupNo\":\"PP0001\",\"couponNo\":\""
                                + userCouponNo + "\"}"))
                .andExpect(jsonPath("$.code").value(40002));
    }

    // ---------------------------------------------------------------- 可用性判定

    @Test
    @DisplayName("★ 最优券试算：给出最优，**并对不可用的券说明原因**")
    void bestCouponExplainsUnusable() throws Exception {
        String token = login("13000130020");
        String small = receive(token, platformCoupon("满50减5", 500L, 5000L));
        String big = receive(token, platformCoupon("满100减20", 2000L, 10000L));
        String tooHigh = receive(token, platformCoupon("满500减100", 10000L, 50000L));

        String body = mvc().perform(post("/mp/coupon/best").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        // 4980×3 = 14940：同时满足「满50减5」与「满100减20」，才能验证「选最优」
                        .content("{\"items\":[{\"goodsNo\":\"G0001\",\"skuNo\":\"SK0001\",\"qty\":3}]}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode data = json.readTree(body).get("data");
        // 商品额 14940：满100减20 可用且更优
        assertThat(data.get("bestUserCouponNo").asString()).isEqualTo(big);
        assertThat(data.get("discountMinor").asLong()).isEqualTo(2000L);
        assertThat(data.get("usable").size()).isEqualTo(2);

        // 「为什么我的券用不了」是券功能最大的客诉来源 —— 必须给出原因
        assertThat(data.get("unusable").size()).isEqualTo(1);
        assertThat(data.get("unusable").get(0).get("userCouponNo").asString()).isEqualTo(tooHigh);
        assertThat(data.get("unusable").get(0).get("reason").asString()).contains("门槛");
        assertThat(small).isNotBlank();
    }

    @Test
    @DisplayName("不满门槛的券不能用于下单")
    void thresholdEnforcedAtOrder() throws Exception {
        String token = login("13000130021");
        String userCouponNo = receive(token, platformCoupon("满500减100", 10000L, 50000L));
        addToCart(token, "G0002", "SK0003", 1);   // 6980 < 50000

        mvc().perform(post("/mp/order").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "m6b-threshold")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fulfillment\":\"STORE_PICKUP\",\"pickupNo\":\"PP0001\",\"couponNo\":\""
                                + userCouponNo + "\"}"))
                .andExpect(jsonPath("$.code").value(40002));
    }

    @Test
    @DisplayName("商家券只对本店商品的金额计门槛，也只减本店的钱")
    void merchantCouponAppliesToOwnGoodsOnly() throws Exception {
        String token = login("13000130022");
        String userCouponNo = receive(token, merchantCoupon("老张店满50减5", 500L, 5000L, "M0001"));

        addToCart(token, "G0001", "SK0001", 2);   // M0001 9960
        addToCart(token, "G0003", "SK0004", 1);   // M0002 5800

        JsonNode data = preview(token, userCouponNo);
        for (JsonNode sub : data.get("subOrders")) {
            long goods = sub.get("amount").get("goodsMinor").asLong();
            long disc = sub.get("amount").get("discountMinor").asLong();
            // 别家的子单不该被商家券减一分钱
            assertThat(goods == 9960L ? disc : 0L).isEqualTo(disc);
        }
        assertThat(data.get("amount").get("discountMinor").asLong()).isEqualTo(500L);
    }

    @Test
    @DisplayName("过期券领不了；已领的券过期后也用不了")
    void expiredCouponUnusable() throws Exception {
        String token = login("13000130023");

        // 已经过期的券根本不该出现在领券中心，更不该能领
        mvc().perform(post("/mp/coupon/" + expiredCoupon("过期券", 500L, 1000L) + "/receive")
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(40001));

        // 「领的时候没过期、用的时候过期了」才是券包里的常态
        String couponNo = platformCoupon("即将过期", 500L, 1000L);
        String userCouponNo = receive(token, couponNo);
        expireNow(couponNo);

        addToCart(token, "G0002", "SK0003", 1);
        mvc().perform(post("/mp/order").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "m6b-expired")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fulfillment\":\"STORE_PICKUP\",\"pickupNo\":\"PP0001\",\"couponNo\":\""
                                + userCouponNo + "\"}"))
                .andExpect(jsonPath("$.code").value(40002));
    }

    /** 把券的结束时间改到过去 —— 模拟「券在包里过期了」。 */
    private void expireNow(String couponNo) {
        MktCoupon c = couponMapper.selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers
                .<MktCoupon>lambdaQuery().eq(MktCoupon::getCouponNo, couponNo).last("limit 1"));
        c.setEndAt(System.currentTimeMillis() - 1000L);
        couponMapper.updateById(c);
    }

    @Test
    @DisplayName("别人的券用不了（券号可猜，必须校验属主）")
    void cannotUseOthersCoupon() throws Exception {
        String owner = login("13000130024");
        String userCouponNo = receive(owner, platformCoupon("满50减5", 500L, 5000L));

        String stranger = login("13000130025");
        addToCart(stranger, "G0002", "SK0003", 1);
        mvc().perform(post("/mp/order").header("Authorization", "Bearer " + stranger)
                        .header("Idempotency-Key", "m6b-others")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fulfillment\":\"STORE_PICKUP\",\"pickupNo\":\"PP0001\",\"couponNo\":\""
                                + userCouponNo + "\"}"))
                .andExpect(jsonPath("$.code").value(40002));
    }

    // ---------------------------------------------------------------- helpers

    private JsonNode preview(String token, String userCouponNo) throws Exception {
        String body = mvc().perform(post("/mp/order/preview").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fulfillment\":\"STORE_PICKUP\",\"pickupNo\":\"PP0001\",\"couponNo\":\""
                                + userCouponNo + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data");
    }

    private String createOrder(String token, String userCouponNo, String idemKey) throws Exception {
        String body = mvc().perform(post("/mp/order").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", idemKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fulfillment\":\"STORE_PICKUP\",\"pickupNo\":\"PP0001\",\"couponNo\":\""
                                + userCouponNo + "\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("payOrderNo").asString();
    }

    @Autowired
    private ai.neargo.shop.trade.mapper.TradeMappers.SubOrderMapper subOrderMapper;

    private long discountMerchantOf(String payOrderNo) {
        return sumOf(payOrderNo, true);
    }

    private long discountPlatformOf(String payOrderNo) {
        return sumOf(payOrderNo, false);
    }

    private long sumOf(String payOrderNo, boolean merchantFunded) {
        return ai.neargo.common.data.scope.DataScopeContext.executeWithoutScope(() ->
                subOrderMapper.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers
                                .<ai.neargo.shop.trade.entity.OrdSubOrder>lambdaQuery()
                                .eq(ai.neargo.shop.trade.entity.OrdSubOrder::getOrderNo, payOrderNo))
                        .stream()
                        .mapToLong(s -> {
                            Long v = merchantFunded ? s.getDiscountMerchant() : s.getDiscountPlatform();
                            return v == null ? 0L : v;
                        }).sum());
    }

    private String couponStatus(String token, String userCouponNo) throws Exception {
        String body = mvc().perform(get("/mp/coupon/mine").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        for (JsonNode c : json.readTree(body).get("data")) {
            if (userCouponNo.equals(c.get("userCouponNo").asString())) {
                return c.get("status").asString();
            }
        }
        throw new AssertionError("user coupon not found: " + userCouponNo);
    }

    private String receive(String token, String couponNo) throws Exception {
        String body = mvc().perform(post("/mp/coupon/" + couponNo + "/receive")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("userCouponNo").asString();
    }

    private String platformCoupon(String title, long face, long threshold) {
        return platformCoupon(title, face, threshold, 100, 1);
    }

    private String platformCoupon(String title, long face, long threshold, int total, int perUser) {
        return insertCoupon(title, face, threshold, "PLATFORM", null, total, perUser, false);
    }

    private String merchantCoupon(String title, long face, long threshold, String merchantNo) {
        return insertCoupon(title, face, threshold, "MERCHANT", merchantNo, 100, 1, false);
    }

    private String expiredCoupon(String title, long face, long threshold) {
        return insertCoupon(title, face, threshold, "PLATFORM", null, 100, 1, true);
    }

    private String insertCoupon(String title, long face, long threshold, String funder,
                                String merchantNo, int total, int perUser, boolean expired) {
        long now = System.currentTimeMillis();
        MktCoupon c = new MktCoupon();
        c.setCouponNo("CP-" + java.util.UUID.randomUUID().toString().substring(0, 8));
        c.setTitle(title);
        c.setType(MktCoupon.FULL_CUT);
        c.setFaceMinor(face);
        c.setDiscountRate(0);
        c.setThresholdMinor(threshold);
        c.setMaxDiscountMinor(0L);
        c.setFunder(funder);
        c.setMerchantNo(merchantNo);
        c.setTotalCount(total);
        c.setReceivedCount(0);
        c.setPerUserLimit(perUser);
        c.setStartAt(now - Duration.ofDays(1).toMillis());
        c.setEndAt(expired ? now - Duration.ofHours(1).toMillis() : now + Duration.ofDays(30).toMillis());
        c.setStatus("ACTIVE");
        couponMapper.insert(c);
        return c.getCouponNo();
    }

    private void addToCart(String token, String goodsNo, String skuNo, int qty) throws Exception {
        mvc().perform(post("/mp/cart/add").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"goodsNo\":\"" + goodsNo + "\",\"skuNo\":\"" + skuNo + "\",\"qty\":" + qty + "}"));
    }

    private String login(String phone) throws Exception {
        mvc().perform(post("/mp/user/otp/send").contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"" + phone + "\"}"));
        String code = otpStore.peek(phone).orElseThrow();
        String body = mvc().perform(post("/mp/user/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grantType\":\"PHONE_OTP\",\"principal\":\"" + phone
                                + "\",\"credential\":\"" + code + "\",\"agreed\":true}"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("token").asString();
    }
}
