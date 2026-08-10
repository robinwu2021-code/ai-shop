package ai.neargo.shop.scenario;

import ai.neargo.shop.marketing.campaign.entity.MktCampaign;
import ai.neargo.shop.marketing.campaign.mapper.CampaignMappers.CampaignMapper;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 店铺活动的自动优惠 —— **补的是一条从来没人测过的断裂**。
 *
 * <p>`mkt_campaign` 表此前**没有任何消费方**：读它的只有它自己的
 * mapper / service / controller。商家在 B 端建了满减活动，后端存下来了，
 * 下单时一分钱不减，而商家侧界面显示活动「进行中」。
 *
 * <p>四层测试当时全绿，因为后端测的是「活动能不能建、字段校验对不对」——
 * **没有任何一条测「建了活动之后，下单金额有没有变」**。
 * 这个文件里的每一条测的都是后者。
 */
@SpringBootTest
@ActiveProfiles("test")
class CampaignDiscountFlowTest {

    /** 本测试造的活动都用这个名字，便于精确清理 */
    private static final String TEST_CAMPAIGN = "测试活动";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;

    @Autowired
    private CampaignMapper campaignMapper;

    @Autowired
    private CouponMapper couponMapper;

    /**
     * 清掉本测试自己造的活动。
     *
     * <p>不清的话测试之间会互相污染：同一个 H2 库里，前一条用例建的 RUNNING 满减
     * 对后一条仍然生效 —— 「未开始/已结束的活动不生效」那条因此会拿到上一条的 800。
     * 按 name 精确删而不是清空整张表：别的用例（以及 DevSeeder）可能也有活动，
     * 清空会把它们一起带走，那种失败最难查。
     */
    @org.junit.jupiter.api.BeforeEach
    void clearOwnCampaigns() {
        ai.neargo.common.data.scope.DataScopeContext.executeWithoutScope(() ->
                campaignMapper.delete(com.baomidou.mybatisplus.core.toolkit.Wrappers
                        .<MktCampaign>lambdaQuery().eq(MktCampaign::getName, TEST_CAMPAIGN)));
    }

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    @DisplayName("★ 满额自动减 —— 商家建的满减活动，下单时真的会减")
    void fullCutApplies() throws Exception {
        String token = login("13000140001");
        // M0001 的商品：4980×2 = 9960，满 5000 减 800
        fullCut("M0001", "满50减8", 5000L, 800L);
        addToCart(token, "G0001", "SK0001", 2);

        JsonNode data = preview(token, null);
        assertThat(data.get("amount").get("discountMinor").asLong()).isEqualTo(800L);
        assertThat(data.get("amount").get("payableMinor").asLong()).isEqualTo(9960L - 800L);
    }

    @Test
    @DisplayName("不满门槛不减 —— 门槛判在后端，不信端上算的那份")
    void belowThresholdNoDiscount() throws Exception {
        String token = login("13000140002");
        fullCut("M0001", "满200减30", 20000L, 3000L);
        addToCart(token, "G0001", "SK0001", 1); // 4980 < 20000

        assertThat(preview(token, null).get("amount").get("discountMinor").asLong()).isZero();
    }

    @Test
    @DisplayName("同店多个满减只取最优的一个，不叠加 —— 叠加会让商家自己算不清成本")
    void bestOneWinsNotStacked() throws Exception {
        String token = login("13000140003");
        fullCut("M0001", "满50减8", 5000L, 800L);
        fullCut("M0001", "满50减5", 5000L, 500L);
        addToCart(token, "G0001", "SK0001", 2);

        // 两个都满足门槛，取 800 而不是 1300
        assertThat(preview(token, null).get("amount").get("discountMinor").asLong()).isEqualTo(800L);
    }

    @Test
    @DisplayName("未开始 / 已结束 / 未启用的活动不生效 —— 判的是下单那一刻")
    void onlyRunningCampaignApplies() throws Exception {
        String token = login("13000140004");
        long now = System.currentTimeMillis();
        // 已结束
        campaign("M0001", MktCampaign.FULL_CUT, MktCampaign.RUNNING, 5000L, 800L,
                now - Duration.ofDays(2).toMillis(), now - Duration.ofDays(1).toMillis());
        // 状态是 PAUSED
        campaign("M0001", MktCampaign.FULL_CUT, MktCampaign.PAUSED, 5000L, 700L,
                now - 1000L, now + Duration.ofDays(1).toMillis());
        addToCart(token, "G0001", "SK0001", 2);

        assertThat(preview(token, null).get("amount").get("discountMinor").asLong()).isZero();
    }

    @Test
    @DisplayName("★ 活动与券叠加：先满减、后券，券作用在满减之后的金额上")
    void couponAppliesAfterCampaign() throws Exception {
        String token = login("13000140005");
        fullCut("M0001", "满50减8", 5000L, 800L);
        String userCouponNo = receive(token, platformCoupon("满80减10", 1000L, 8000L));
        addToCart(token, "G0001", "SK0001", 2); // 9960

        JsonNode data = preview(token, userCouponNo);
        /*
         * 9960 -800(活动) = 9160，仍 ≥ 8000 门槛 → 再 -1000(券) = 8160。
         * 顺序反过来（先券后活动）总额一样，但「券帮我省了多少」在用户那里对不上 ——
         * 他看到的券减免应该是已有优惠之上的增量。
         */
        assertThat(data.get("amount").get("discountMinor").asLong()).isEqualTo(1800L);
        assertThat(data.get("amount").get("payableMinor").asLong()).isEqualTo(8160L);
    }

    @Test
    @DisplayName("★ 活动优惠恒记商家出资 —— 店铺活动平台不掏这个钱")
    void campaignDiscountIsMerchantFunded() throws Exception {
        String token = login("13000140006");
        fullCut("M0001", "满50减8", 5000L, 800L);
        addToCart(token, "G0001", "SK0001", 2);

        String payOrderNo = createOrder(token, null, "campaign-funder");
        // 出资方决定 M7 分账扣谁的钱；活动是商家自己建的，钱当然由商家出
        assertThat(discountMerchantOf(payOrderNo)).isEqualTo(800L);
        assertThat(discountPlatformOf(payOrderNo)).isZero();
    }

    // ---------------------------------------------------------------- 店铺券桥接

    @Test
    @DisplayName("★ 商家建的店铺券活动 → 领券中心真的能领到")
    void merchantCouponCampaignBecomesReceivableCoupon() throws Exception {
        String bizToken = merchant("12600150901", "券桥接·可领");
        String campaignNo = saveCouponCampaign(bizToken, "店庆券 满50减5", 5000L, 500L);
        toggleCampaign(bizToken, campaignNo, true);

        String token = login("13000150001");
        JsonNode center = centerCoupons(token);
        JsonNode mine = findCoupon(center, "店庆券 满50减5");
        assertThat(mine).as("建了店铺券活动，领券中心却看不到 —— 这正是此前断掉的那半段").isNotNull();

        // 领得到（此前这一步无从谈起：根本没有券）
        String userCouponNo = receive(token, mine.get("couponNo").asString());
        assertThat(userCouponNo).isNotBlank();

        /*
         * 顺带锁住一条**正确但容易被当成 bug** 的行为：商家券只作用于本店。
         * 这条断言最初是写错的 —— 我拿新商家的券去抵扣 M0001 的商品，
         * 期望它减 500，结果被拒（40002）。被拒才是对的：
         * 商家自己出资的券去抵别家的货，等于让 A 商家替 B 商家掏钱。
         */
        addToCart(token, "G0001", "SK0001", 2); // G0001 属于 M0001，不是这张券的店
        mvc().perform(post("/mp/order/preview").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fulfillment\":\"STORE_PICKUP\",\"pickupNo\":\"PP0001\",\"couponNo\":\""
                                + userCouponNo + "\"}"))
                .andExpect(jsonPath("$.code").value(40002));
    }

    @Test
    @DisplayName("重复保存同一个活动只对应一张券 —— 不能存一次多发一张")
    void savingTwiceDoesNotDuplicateCoupon() throws Exception {
        String bizToken = merchant("12600150902", "券桥接·不重复");
        String campaignNo = saveCouponCampaign(bizToken, "只发一张", 5000L, 300L);
        toggleCampaign(bizToken, campaignNo, true);
        // 改个名再存一次
        saveCouponCampaign(bizToken, "只发一张", 5000L, 300L, campaignNo);

        String token = login("13000150002");
        long n = 0;
        for (JsonNode c : centerCoupons(token)) {
            if ("只发一张".equals(c.get("title").asString())) n++;
        }
        assertThat(n).isEqualTo(1L);
    }

    @Test
    @DisplayName("暂停活动，券停止发放 —— 但已领的不受影响")
    void pausingCampaignStopsIssuing() throws Exception {
        String bizToken = merchant("12600150903", "券桥接·暂停");
        String campaignNo = saveCouponCampaign(bizToken, "会被暂停的券", 5000L, 400L);
        toggleCampaign(bizToken, campaignNo, true);

        String token = login("13000150003");
        String couponNo = findCoupon(centerCoupons(token), "会被暂停的券").get("couponNo").asString();
        String userCouponNo = receive(token, couponNo);

        toggleCampaign(bizToken, campaignNo, false);

        // 领券中心不再出现
        String other = login("13000150004");
        assertThat(findCoupon(centerCoupons(other), "会被暂停的券")).isNull();
        // 但已领的那张还在用户券包里 —— 那是他已经拿到手的东西，停发不等于收回
        assertThat(userCouponNo).isNotBlank();
        String bag = mvc().perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/mp/coupon/mine").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(bag).contains(userCouponNo);
    }

    // ---------------------------------------------------------------- 装配

    private void fullCut(String entityNo, String name, long threshold, long off) {
        long now = System.currentTimeMillis();
        campaign(entityNo, MktCampaign.FULL_CUT, MktCampaign.RUNNING, threshold, off,
                now - 1000L, now + Duration.ofDays(7).toMillis());
    }

    private void campaign(String entityNo, String type, String status,
                          long threshold, long off, long startAt, long endAt) {
        MktCampaign c = new MktCampaign();
        c.setCampaignNo("CP" + System.nanoTime());
        c.setEntityNo(entityNo);
        c.setType(type);
        c.setName(TEST_CAMPAIGN);
        c.setStatus(status);
        c.setThresholdMinor(threshold);
        c.setDiscountMinor(off);
        c.setStartAt(startAt);
        c.setEndAt(endAt);
        campaignMapper.insert(c);
    }

    private String platformCoupon(String title, long face, long threshold) {
        MktCoupon c = new MktCoupon();
        c.setCouponNo("CU" + System.nanoTime());
        c.setTitle(title);
        c.setType(MktCoupon.FULL_CUT);
        c.setFaceMinor(face);
        c.setThresholdMinor(threshold);
        c.setFunder(MktCoupon.BY_PLATFORM);
        c.setPerUserLimit(1);
        c.setStartAt(System.currentTimeMillis() - 1000L);
        c.setEndAt(System.currentTimeMillis() + Duration.ofDays(7).toMillis());
        c.setStatus("ACTIVE");
        couponMapper.insert(c);
        return c.getCouponNo();
    }

    private String receive(String token, String couponNo) throws Exception {
        String body = mvc().perform(post("/mp/coupon/" + couponNo + "/receive")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("userCouponNo").asString();
    }

    private void addToCart(String token, String goodsNo, String skuNo, int qty) throws Exception {
        mvc().perform(post("/mp/cart/add").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goodsNo\":\"" + goodsNo + "\",\"skuNo\":\"" + skuNo
                                + "\",\"qty\":" + qty + "}"))
                .andExpect(status().isOk());
    }

    private JsonNode preview(String token, String userCouponNo) throws Exception {
        String coupon = userCouponNo == null ? "" : ",\"couponNo\":\"" + userCouponNo + "\"";
        String body = mvc().perform(post("/mp/order/preview").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fulfillment\":\"STORE_PICKUP\",\"pickupNo\":\"PP0001\"" + coupon + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data");
    }

    private String createOrder(String token, String userCouponNo, String idemKey) throws Exception {
        String coupon = userCouponNo == null ? "" : ",\"couponNo\":\"" + userCouponNo + "\"";
        String body = mvc().perform(post("/mp/order").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", idemKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fulfillment\":\"STORE_PICKUP\",\"pickupNo\":\"PP0001\"" + coupon + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("orderNo").asString();
    }

    private long discountMerchantOf(String orderNo) throws Exception {
        return subOrderField(orderNo, "discountMerchant");
    }

    private long discountPlatformOf(String orderNo) throws Exception {
        return subOrderField(orderNo, "discountPlatform");
    }

    @Autowired
    private ai.neargo.shop.trade.mapper.TradeMappers.SubOrderMapper subOrderMapper;

    private long subOrderField(String orderNo, String field) {
        var subs = ai.neargo.common.data.scope.DataScopeContext.executeWithoutScope(() ->
                subOrderMapper.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers
                        .<ai.neargo.shop.trade.entity.OrdSubOrder>lambdaQuery()
                        .eq(ai.neargo.shop.trade.entity.OrdSubOrder::getOrderNo, orderNo)));
        return subs.stream()
                .mapToLong(s -> "discountMerchant".equals(field)
                        ? nz(s.getDiscountMerchant()) : nz(s.getDiscountPlatform()))
                .sum();
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }

    /** 商家会话：走完整入驻 + 运营审核，与 BizDashboardAndReviewFlowTest 同一套 */
    private String merchant(String phone, String name) throws Exception {
        String user = login(phone);
        String body = mvc().perform(post("/mp/merchant/apply").header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"subject\":\"INDIVIDUAL_BIZ\","
                                + "\"contactName\":\"张三\",\"contactPhone\":\"13900000000\","
                                + "\"category\":\"食品\",\"serviceScope\":\"COMMUNITY\","
                                + "\"communityNos\":[\"CM001\"]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        String applyNo = json.readTree(body).get("data").get("applyNo").asString();

        String bd = opsLogin();
        mvc().perform(post("/ops/merchant/apply/" + applyNo + "/audit")
                        .header("Authorization", "Bearer " + bd)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"))
                .andExpect(jsonPath("$.code").value(0));
        return login(phone);
    }

    private String opsLogin() throws Exception {
        String body = mvc().perform(post("/ops/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"bd\",\"password\":\"bd123\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("token").asString();
    }

    private String saveCouponCampaign(String token, String name, long threshold, long off)
            throws Exception {
        return saveCouponCampaign(token, name, threshold, off, null);
    }

    private String saveCouponCampaign(String token, String name, long threshold, long off,
                                      String campaignNo) throws Exception {
        long now = System.currentTimeMillis();
        String no = campaignNo == null ? "" : ",\"campaignNo\":\"" + campaignNo + "\"";
        String body = mvc().perform(post("/biz/campaign").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"COUPON\",\"name\":\"" + name + "\",\"startAt\":"
                                + (now - 1000L) + ",\"endAt\":" + (now + Duration.ofDays(7).toMillis())
                                + ",\"thresholdMinor\":" + threshold + ",\"discountMinor\":" + off
                                + ",\"goodsNos\":[]" + no + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("campaignNo").asString();
    }

    private void toggleCampaign(String token, String campaignNo, boolean running) throws Exception {
        mvc().perform(post("/biz/campaign/" + campaignNo + "/toggle")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"running\":" + running + "}"))
                .andExpect(status().isOk());
    }

    private JsonNode centerCoupons(String token) throws Exception {
        String body = mvc().perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/mp/coupon").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data");
    }

    private JsonNode findCoupon(JsonNode list, String title) {
        for (JsonNode c : list) {
            if (title.equals(c.get("title").asString())) return c;
        }
        return null;
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
