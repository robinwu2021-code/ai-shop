package ai.neargo.shop.scenario;

import ai.neargo.shop.support.TestLogin;
import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.merchant.entity.MchEntity;
import ai.neargo.shop.product.entity.PrdSku;
import ai.neargo.shop.product.mapper.ProductMappers;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchEntityMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M5 售后 —— **用例先行**（任务清单 §二 .4 步）。
 *
 * <p>本模块只有一条真正的红线：**退款前必须先回退分账**（E4 / A2 §1 不变量）。
 * 顺序反了，钱退给了用户但分账收不回来 —— 是真金白银的损失，且事后只能人工追。
 * 因此这条由**状态机强制**，并在这里用一条独立用例守住。
 */
@SpringBootTest
@ActiveProfiles("test")
class M5AfterSaleFlowTest {

    private static final String STUB_SECRET = "stub-secret";

    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;

    @Autowired
    private WebApplicationContext context;

    /** 直接读实存用 —— 断言不该经过任何会顺手改数的接口。 */
    @Autowired
    private ProductMappers.SkuMapper skuMapper;

    @Autowired
    private ai.neargo.shop.merchant.mapper.MerchantMappers.MchEntityMapper entityMapperForFunds;

    /**
     * 本类测的是<b>第三方商家的售后流程</b>（商家审 → 驳回 → 升级平台仲裁）。
     *
     * <p>那条流程只在 {@code funds_mode=DIRECT} 下存在：钱在商家二级户、票是他开的，
     * 所以先由他处理。而归集路径下平台是销售主体，售后<b>直接进平台仲裁</b>
     * （ADR-017 §3.4 条件 3），根本不经过商家。
     *
     * <p>M0001 在 V87 之后是归集，于是这些用例全部走进了自营分支。
     * <b>显式把它设成直连</b>，而不是放宽断言 —— 断言本身没错，是 fixture 没说清它在测哪条路。
     */
    @org.junit.jupiter.api.BeforeEach
    void makeMerchantDirect() {
        ai.neargo.common.data.scope.DataScopeContext.executeWithoutScope(() -> {
            var e = entityMapperForFunds.selectOne(
                    com.baomidou.mybatisplus.core.toolkit.Wrappers
                            .<ai.neargo.shop.merchant.entity.MchEntity>lambdaQuery()
                            .eq(ai.neargo.shop.merchant.entity.MchEntity::getEntityNo, "M0001")
                            .last("LIMIT 1"));
            if (e != null) {
                e.setFundsMode(ai.neargo.shop.spi.user.MerchantQueryPort.FUNDS_DIRECT);
                entityMapperForFunds.updateById(e);
            }
            return null;
        });
    }

    @Autowired
    private ObjectMapper json;


    @Autowired
    private MchEntityMapper merchantMapper;

    /** 整单退款退券那条用例要自己造一张券（与 M6bCouponFlowTest 同一手法） */
    @Autowired
    private ai.neargo.shop.marketing.coupon.mapper.CouponMappers.CouponMapper couponTemplateMapper;

    @Autowired
    private ai.neargo.shop.pay.SettleService settleService;

    /** M7 之后 SettlePort 是真实实现；失败注入下沉到**注定被替换的**通道桩上。 */
    @Autowired
    private ai.neargo.shop.pay.StubSplitGateway splitGateway;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    // ---------------------------------------------------------------- 资损红线

    @Test
    @DisplayName("★★ 售后单要带 updatedAt 与商家回复 —— 契约里有、后端不发，两个端都静默少一块")
    void afterSaleCarriesReplyAndUpdatedAt() throws Exception {
        Ordered o = placeAndPay("13200132090", 6980L);
        String asNo = applyAfterSale(o, "RETURN_REFUND", "质量问题");
        String biz = loginAsOwnerOf("M0001", "13200132091");
        mvc().perform(post("/biz/after-sale/" + asNo + "/approve")
                        .header("Authorization", "Bearer " + biz)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"remark\":\"同意，寄回后退款\"}"))
                .andExpect(jsonPath("$.code").value(0));

        /*
         * 三个字段各对应一处「静默少一块」：
         *   · updatedAt     —— B 端售后页每行的时间，缺了显示 NaN-NaN-NaN NaN:NaN
         *   · merchantReply —— C 端订单页「商家回复：」整块不渲染，看着像商家没回
         *   · createdAt     —— 申请时间
         * 前两个此前要么没发、要么发的是库列名（merchantRemark），
         * 而契约里两个名字都写着 —— 端上照契约写，屏幕上就少一块。
         */
        JsonNode as = json.readTree(detail(o.userToken, asNo)).get("data");
        assertThat(as.get("updatedAt").asLong()).as("updatedAt 必须有值").isGreaterThan(0L);
        assertThat(as.get("createdAt").asLong()).isGreaterThan(0L);
        assertThat(as.get("merchantReply").asString())
                .as("契约叫 merchantReply，不是库里的 merchantRemark")
                .isEqualTo("同意，寄回后退款");
    }

    @Test
    @DisplayName("★ 退款前必须先回退分账（E4）—— 顺序由状态机强制")
    void splitMustBeReversedBeforeRefund() throws Exception {
        Ordered o = placeAndPay("13200132001", 6980L);
        String asNo = applyAfterSale(o, "REFUND_ONLY", "不想要了");
        String biz = loginAsOwnerOf("M0001", "13200132002");

        approve(biz, asNo);

        String body = detail(o.userToken, asNo);
        JsonNode data = json.readTree(body).get("data");
        assertThat(data.get("status").asString()).isEqualTo("REFUNDED");

        // 分账回退必须**发生在退款之前**：退款成功 ⟹ 结算单已回退。
        // 反过来说，只要结算单还没回退，就绝不可能出现 REFUNDED（下一条用例守住反向）
        assertThat(json.readTree(detail(o.userToken, asNo)).get("data").get("status").asString())
                .isEqualTo("REFUNDED");
    }

    @Test
    @DisplayName("★★★ 整单退款退回券 —— 退了货还扣着券，用户会说「东西退了券也没了」")
    void couponReturnedOnWholeOrderRefund() throws Exception {
        String phone = "13200132500";
        String token = login(phone);

        // 一张无门槛平台券，领到手里
        long now = System.currentTimeMillis();
        ai.neargo.shop.marketing.coupon.entity.MktCoupon c =
                new ai.neargo.shop.marketing.coupon.entity.MktCoupon();
        c.setCouponNo("CP-REFUND-" + java.util.UUID.randomUUID().toString().substring(0, 8));
        c.setTitle("退款退券测试");
        c.setType("FULL_CUT");
        c.setFaceMinor(500L);
        c.setThresholdMinor(0L);
        c.setFunder("PLATFORM");
        c.setTotalCount(100);
        c.setPerUserLimit(1);
        c.setStartAt(now - 86_400_000L);
        c.setEndAt(now + 86_400_000L);
        c.setStatus("ACTIVE");
        couponTemplateMapper.insert(c);

        String receiveBody = mvc().perform(post("/mp/coupon/" + c.getCouponNo() + "/receive")
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        String userCouponNo = json.readTree(receiveBody).get("data").get("userCouponNo").asString();

        // 用这张券下单并付款
        mvc().perform(post("/mp/cart/add").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"goodsNo\":\"G0002\",\"skuNo\":\"SK0003\",\"qty\":1}"));
        String orderBody = mvc().perform(post("/mp/order").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "m5-refund-coupon")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fulfillment\":\"STORE_PICKUP\",\"pickupNo\":\"PP0001\","
                                + "\"couponNo\":\"" + userCouponNo + "\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        String payOrderNo = json.readTree(orderBody).get("data").get("payOrderNo").asString();
        mvc().perform(post("/pay/callback/stub").contentType(MediaType.APPLICATION_JSON)
                .content("{\"outTradeNo\":\"" + payOrderNo + "\",\"transactionId\":\"TX-m5-refund\","
                        + "\"sign\":\"" + STUB_SECRET + "\"}"));
        assertThat(couponStatusOf(token, userCouponNo)).isEqualTo("USED");

        String subNo = json.readTree(orderBody).get("data").get("subOrders").get(0)
                .get("orderNo").asString();
        String asNo = mvcApply(token, subNo);
        String biz = loginAsOwnerOf("M0001", "13200132501");
        approve(biz, asNo);

        /*
         * **整单退完，券要回到券包**（执行计划 B6，用户 2026-09-21 拍板）。
         *
         * 不退的话用户的说法是「东西我退了，券也没了」—— 而那张券本来还能用。
         * 口径是整单退才退：部分退还要按比例拆券，那是另一套账。
         */
        assertThat(couponStatusOf(token, userCouponNo))
                .as("整单退款没退券 —— 用户会认为平台吞了券").isEqualTo("UNUSED");
    }

    // ---------------------------------------------------------------- P2a / P3：整单退款退积分、说清去向

    @Autowired
    private ai.neargo.shop.pay.mapper.SettleMappers.PointsAccountMapper pointsAccountMapper;

    @Autowired
    private ai.neargo.shop.community.mapper.CommunityMappers.CommunityMapper communityMapperForPoints;

    @Autowired
    private ai.neargo.shop.platform.PlatformConfigService platformConfig;

    @Test
    @DisplayName("★★★ 整单退款退回抵扣积分，并在 C 端与 B 端详情里说清「退回了多少」（P2a / P3）")
    void pointsReturnedOnWholeOrderRefund() throws Exception {
        var restore = openPoints();
        try {
            String token = login("13200132510");
            String userNo = userNoOf(token);
            givePoints(userNo, 10_000L);

            String subNo = payWithPoints(token, 800L, "m5-refund-points");
            assertThat(pointsBalanceOf(userNo)).isEqualTo(9_200L);
            long earned = pendingOf(userNo);
            assertThat(earned).as("付款没发分，后面「收回」那条断言就没有意义").isPositive();

            String asNo = mvcApply(token, subNo);
            String owner = loginAsOwnerOf("M0001", "13200132511");
            /*
             * P2c · B 端：**同意之前**就要看见会一并退回什么 ——
             * 商家券的退回让他少收一次核销，点下去之后再说就晚了。
             */
            JsonNode rows = json.readTree(mvc().perform(get("/biz/after-sale")
                            .header("Authorization", "Bearer " + owner))
                    .andReturn().getResponse().getContentAsString()).get("data");
            JsonNode impact = null;
            for (JsonNode r : rows) {
                if (asNo.equals(r.get("afterSaleNo").asString())) {
                    impact = r.get("impact");
                }
            }
            assertThat(impact).as("同意前看不到会退回什么").isNotNull();
            assertThat(impact.get("pointsReturn").asLong()).isEqualTo(800L);
            assertThat(impact.get("pointsRevoke").asLong()).isEqualTo(earned);

            approve(owner, asNo);

            assertThat(pointsBalanceOf(userNo))
                    .as("整单退了，抵扣的 800 分没回来 —— 用户会说「钱退了分没了」").isEqualTo(10_000L);

            JsonNode returned = orderDetail(token, subNo).get("returned");
            assertThat(returned).as("退款后的详情要说清去向").isNotNull();
            assertThat(returned.get("pointsReturned").asLong()).isEqualTo(800L);
            // P2c：货钱都退了，买它得的分也收回 —— 不收的话「买了退、退了买」就能刷分
            assertThat(pendingOf(userNo)).as("赠送的分没收回").isZero();
            assertThat(returned.get("pointsClawedBack").asLong()).isEqualTo(earned);

            String biz = loginAsOwnerOf("M0001", "13200132511");
            JsonNode bizDetail = json.readTree(mvc().perform(get("/biz/order/" + subNo)
                            .header("Authorization", "Bearer " + biz))
                    .andReturn().getResponse().getContentAsString()).get("data");
            assertThat(bizDetail.get("returned").get("pointsReturned").asLong())
                    .as("商家客服接到「我的分呢」要看得到").isEqualTo(800L);
        } finally {
            restore.run();
        }
    }

    @Test
    @DisplayName("★★ 开关 refund.return-points 关掉 = 不退分（关着的那一半也要测）")
    void pointsKeptWhenSwitchOff() throws Exception {
        var restore = openPoints();
        platformConfig.saveFeatureFlag("refund.return-points", false, 0, "TEST");
        try {
            String token = login("13200132512");
            String userNo = userNoOf(token);
            givePoints(userNo, 10_000L);
            String subNo = payWithPoints(token, 500L, "m5-refund-points-off");

            approve(loginAsOwnerOf("M0001", "13200132513"), mvcApply(token, subNo));

            assertThat(pointsBalanceOf(userNo)).isEqualTo(9_500L);
            /*
             * 没退就不说 —— 「已退回」若不成立比什么都不说更糟。
             * 只看「退回」这一项：赠送分的收回是另一个开关，此时照常发生，去向块里会有它。
             */
            JsonNode returned = orderDetail(token, subNo).get("returned");
            assertThat(absent(returned) || returned.get("pointsReturned").asLong() == 0L)
                    .as("开关关着却说「已退回」").isTrue();
        } finally {
            platformConfig.saveFeatureFlag("refund.return-points", true, 0, "TEST");
            restore.run();
        }
    }

    @Test
    @DisplayName("★★ 取消的单详情里说出券已回到券包（P3）")
    void cancelledOrderSaysCouponReturned() throws Exception {
        String token = login("13200132514");
        long now = System.currentTimeMillis();
        ai.neargo.shop.marketing.coupon.entity.MktCoupon c =
                new ai.neargo.shop.marketing.coupon.entity.MktCoupon();
        c.setCouponNo("CP-RET-" + java.util.UUID.randomUUID().toString().substring(0, 8));
        c.setTitle("去向测试券");
        c.setType("FULL_CUT");
        c.setFaceMinor(300L);
        c.setThresholdMinor(0L);
        c.setFunder("PLATFORM");
        c.setTotalCount(100);
        c.setPerUserLimit(1);
        c.setStartAt(now - 86_400_000L);
        c.setEndAt(now + 86_400_000L);
        c.setStatus("ACTIVE");
        couponTemplateMapper.insert(c);
        String userCouponNo = json.readTree(mvc().perform(post("/mp/coupon/" + c.getCouponNo() + "/receive")
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString()).get("data").get("userCouponNo").asString();

        mvc().perform(post("/mp/cart/add").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"goodsNo\":\"G0002\",\"skuNo\":\"SK0003\",\"qty\":1}"));
        JsonNode order = json.readTree(mvc().perform(post("/mp/order").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "m5-returned-coupon")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fulfillment\":\"STORE_PICKUP\",\"pickupNo\":\"PP0001\","
                                + "\"couponNo\":\"" + userCouponNo + "\"}"))
                .andReturn().getResponse().getContentAsString()).get("data");
        String subNo = order.get("subOrders").get(0).get("orderNo").asString();
        assertThat(absent(orderDetail(token, subNo).get("returned"))).as("还没关的单不给去向").isTrue();

        mvc().perform(post("/mp/order/" + order.get("orderNo").asString() + "/cancel")
                .header("Authorization", "Bearer " + token)).andExpect(jsonPath("$.code").value(0));

        assertThat(orderDetail(token, subNo).get("returned").get("couponTitle").asString())
                .isEqualTo("去向测试券");
    }

    @Autowired
    private ai.neargo.shop.promotion.service.CouponService promoCoupons;

    @Test
    @DisplayName("★★★ 用新券下单后，C 端与 B 端详情都说得出券名与出资方（批 3 · B8）")
    void detailNamesNewCouponWithFunder() throws Exception {
        String title = "详情券名" + System.nanoTime() % 100000;
        String couponNo = promoCoupons.save("M0001", new ai.neargo.shop.promotion.dto.CouponVOs.CouponSaveCmd(
                null, title, "CASH", 300L, null, null, 1_000L, null, "ALL", java.util.List.of(), null,
                "RELATIVE", null, null, 7, "CENTER", "ORDER", 1, 10, 1, null), "OP").couponNo();
        try {
            String token = login("13200132520");
            String userCouponNo = json.readTree(mvc().perform(post("/mp/coupon/" + couponNo + "/receive")
                            .header("Authorization", "Bearer " + token))
                    .andReturn().getResponse().getContentAsString()).get("data").get("userCouponNo").asString();
            mvc().perform(post("/mp/cart/add").header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"goodsNo\":\"G0002\",\"skuNo\":\"SK0003\",\"qty\":1}"));
            JsonNode order = json.readTree(mvc().perform(post("/mp/order").header("Authorization", "Bearer " + token)
                            .header("Idempotency-Key", "m5-detail-coupon-" + System.nanoTime())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"fulfillment\":\"STORE_PICKUP\",\"pickupNo\":\"PP0001\","
                                    + "\"couponNo\":\"" + userCouponNo + "\"}"))
                    .andReturn().getResponse().getContentAsString()).get("data");
            String subNo = order.get("subOrders").get(0).get("orderNo").asString();

            JsonNode cLine = couponLine(orderDetail(token, subNo));
            assertThat(cLine).as("新券在详情里说不出名字 —— 此前按持有的券号去查模板，一条都查不到").isNotNull();
            assertThat(cLine.get("name").asString()).isEqualTo(title);
            assertThat(cLine.get("funder").asString()).isEqualTo("MERCHANT");

            String biz = loginAsOwnerOf("M0001", "13200132521");
            JsonNode bizDetail = json.readTree(mvc().perform(get("/biz/order/" + subNo)
                            .header("Authorization", "Bearer " + biz))
                    .andReturn().getResponse().getContentAsString()).get("data");
            assertThat(couponLine(bizDetail)).as("商家看不到这单减了什么").isNotNull();
        } finally {
            promoCoupons.setStatus("M0001", couponNo, "ENDED");
        }
    }

    private static JsonNode couponLine(JsonNode detail) {
        JsonNode lines = detail == null ? null : detail.get("discountLines");
        if (lines == null) {
            return null;
        }
        for (JsonNode l : lines) {
            if ("COUPON".equals(l.get("kind").asString())) {
                return l;
            }
        }
        return null;
    }

    /** 付款：带抵扣下单 + 回调，返回子单号 */
    private String payWithPoints(String token, long usePoints, String idem) throws Exception {
        mvc().perform(post("/mp/cart/add").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"goodsNo\":\"G0002\",\"skuNo\":\"SK0003\",\"qty\":1}"));
        JsonNode order = json.readTree(mvc().perform(post("/mp/order").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", idem)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fulfillment\":\"STORE_PICKUP\",\"pickupNo\":\"PP0001\","
                                + "\"usePoints\":" + usePoints + "}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString()).get("data");
        mvc().perform(post("/pay/callback/stub").contentType(MediaType.APPLICATION_JSON)
                .content("{\"outTradeNo\":\"" + order.get("payOrderNo").asString()
                        + "\",\"transactionId\":\"TX-" + idem + "\",\"sign\":\"" + STUB_SECRET + "\"}"));
        return order.get("subOrders").get(0).get("orderNo").asString();
    }

    private static boolean absent(JsonNode n) {
        return n == null || n.isNull();
    }

    private JsonNode orderDetail(String token, String subNo) throws Exception {
        return json.readTree(mvc().perform(get("/mp/order/" + subNo)
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString()).get("data");
    }

    private String userNoOf(String token) throws Exception {
        return json.readTree(mvc().perform(get("/mp/user/profile").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString()).get("data").get("userNo").asString();
    }

    private void givePoints(String userNo, long points) {
        var a = new ai.neargo.shop.pay.entity.PtsUserAccount();
        a.setUserNo(userNo);
        a.setBalance(points);
        a.setPendingBalance(0L);
        a.setTotalEarn(points);
        a.setTotalUse(0L);
        a.setMarket("CN");
        a.setCreatedAt(java.time.LocalDateTime.now());
        a.setUpdatedAt(java.time.LocalDateTime.now());
        pointsAccountMapper.insert(a);
    }

    private long pendingOf(String userNo) {
        var a = pointsAccountMapper.selectOne(Wrappers.<ai.neargo.shop.pay.entity.PtsUserAccount>lambdaQuery()
                .eq(ai.neargo.shop.pay.entity.PtsUserAccount::getUserNo, userNo).last("LIMIT 1"));
        return a == null || a.getPendingBalance() == null ? 0L : a.getPendingBalance();
    }

    private long pointsBalanceOf(String userNo) {
        var a = pointsAccountMapper.selectOne(Wrappers.<ai.neargo.shop.pay.entity.PtsUserAccount>lambdaQuery()
                .eq(ai.neargo.shop.pay.entity.PtsUserAccount::getUserNo, userNo).last("LIMIT 1"));
        return a == null || a.getBalance() == null ? 0L : a.getBalance();
    }

    /**
     * 打开积分的社区与商家开关（默认全关），**返回还原动作** —— 这两处是共享种子，
     * 开着不还会让后面每一个「没开积分就不抵」的用例都变红，而报错指向它们自己。
     */
    private Runnable openPoints() {
        return DataScopeContext.executeWithoutScope(() -> {
            java.util.Map<Long, Boolean> cmt = new java.util.HashMap<>();
            for (var c : communityMapperForPoints.selectList(null)) {
                cmt.put(c.getId(), c.getPointsEnabled());
                c.setPointsEnabled(true);
                communityMapperForPoints.updateById(c);
            }
            java.util.Map<Long, Boolean> mch = new java.util.HashMap<>();
            for (MchEntity m : entityMapperForFunds.selectList(null)) {
                mch.put(m.getId(), m.getPointsEnabled());
                m.setPointsEnabled(true);
                entityMapperForFunds.updateById(m);
            }
            return (Runnable) () -> DataScopeContext.executeWithoutScope(() -> {
                cmt.forEach((id, v) -> communityMapperForPoints.update(null,
                        Wrappers.<ai.neargo.shop.community.entity.CmtCommunity>lambdaUpdate()
                                .set(ai.neargo.shop.community.entity.CmtCommunity::getPointsEnabled, v)
                                .eq(ai.neargo.shop.community.entity.CmtCommunity::getId, id)));
                mch.forEach((id, v) -> entityMapperForFunds.update(null,
                        Wrappers.<MchEntity>lambdaUpdate().set(MchEntity::getPointsEnabled, v)
                                .eq(MchEntity::getId, id)));
                return null;
            });
        });
    }

    /** 这一单的券此刻什么状态 */
    private String couponStatusOf(String token, String userCouponNo) throws Exception {
        String body = mvc().perform(get("/mp/coupon/mine").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        for (JsonNode n : json.readTree(body).get("data")) {
            if (userCouponNo.equals(n.get("userCouponNo").asString())) {
                return n.get("status").asString();
            }
        }
        throw new AssertionError("user coupon not found: " + userCouponNo);
    }

    /** 申请一张仅退款的售后单，返回单号 */
    private String mvcApply(String token, String subOrderNo) throws Exception {
        String body = mvc().perform(post("/mp/order/" + subOrderNo + "/after-sale")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"REFUND_ONLY\",\"reason\":\"不想要了\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("afterSaleNo").asString();
    }

    @Test
    @DisplayName("分账回退失败时不得退款（钱只能少退，不能多退）")
    void refundBlockedWhenReverseFails() throws Exception {
        Ordered o = placeAndPay("13200132003", 6980L);
        String asNo = applyAfterSale(o, "REFUND_ONLY", "分账回退会失败");
        String biz = loginAsOwnerOf("M0001", "13200132004");

        // 让分账回退失败：此时**绝不能退款**
        prepareSplit(o.subOrderNo);
        splitGateway.failNext(o.subOrderNo);
        mvc().perform(post("/biz/after-sale/" + asNo + "/approve").header("Authorization", "Bearer " + biz))
                .andExpect(jsonPath("$.code").value(50002));

        // 停在 REFUNDING 等重试，而不是「退款成功」
        assertThat(json.readTree(detail(o.userToken, asNo)).get("data").get("status").asString())
                .isNotEqualTo("REFUNDED");
    }

    // ---------------------------------------------------------------- 申请与流转

    @Test
    @DisplayName("申请售后：子单粒度，带凭证图，进入待商家处理")
    void applyAfterSale() throws Exception {
        Ordered o = placeAndPay("13200132010", 6980L);
        String asNo = applyAfterSale(o, "RETURN_REFUND", "少发一件");

        mvc().perform(get("/mp/after-sale/" + asNo).header("Authorization", "Bearer " + o.userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.afterSaleNo").value(asNo))
                .andExpect(jsonPath("$.data.subOrderNo").value(o.subOrderNo))
                .andExpect(jsonPath("$.data.status").value("APPLIED"))
                .andExpect(jsonPath("$.data.images.length()").value(1))
                .andExpect(jsonPath("$.data.timeline.length()").value(1));
    }

    @Test
    @DisplayName("极速退：小额自动通过，商家只可见不可拒")
    void instantRefundAutoApproved() throws Exception {
        // 阈值内金额（配置默认 100 元以内）
        Ordered o = placeAndPay("13200132011", 3980L);
        String asNo = applyAfterSale(o, "REFUND_ONLY", "买错了");

        String body = detail(o.userToken, asNo);
        JsonNode data = json.readTree(body).get("data");
        assertThat(data.get("instant").asBoolean()).isTrue();
        // 自动通过：用户不用等商家点同意
        assertThat(data.get("status").asString()).isEqualTo("REFUNDED");

        // 3980 那件是 M0002（鲜果直供）的货 —— 售后归属跟着商品走，不是跟着自提点走
        String biz = loginAsOwnerOf("M0002", "13200132012");
        mvc().perform(post("/biz/after-sale/" + asNo + "/reject").header("Authorization", "Bearer " + biz)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"remark\":\"我不同意\"}"))
                .andExpect(jsonPath("$.code").value(20004));   // 状态机拒绝
    }

    @Test
    @DisplayName("商家驳回必须写理由，用户可申诉上升平台")
    void rejectThenEscalate() throws Exception {
        Ordered o = placeAndPay("13200132013", 6980L);
        String asNo = applyAfterSale(o, "RETURN_REFUND", "质量问题");
        String biz = loginAsOwnerOf("M0001", "13200132014");

        mvc().perform(post("/biz/after-sale/" + asNo + "/reject").header("Authorization", "Bearer " + biz)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"remark\":\"商品无质量问题\"}"))
                .andExpect(jsonPath("$.data.status").value("REJECTED"))
                .andExpect(jsonPath("$.data.merchantReply").value("商品无质量问题"));

        mvc().perform(post("/mp/after-sale/" + asNo + "/escalate")
                        .header("Authorization", "Bearer " + o.userToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"appeal\":\"有照片为证\"}"))
                .andExpect(jsonPath("$.data.status").value("ARBITRATING"));
    }

    // ---------------------------------------------------------------- 平台仲裁（P-6.1）

    @Test
    @DisplayName("★ ARBITRATING 的出口：平台支持用户 → 单子推进到退款")
    void platformArbitratesForUser() throws Exception {
        String asNo = escalated("13200133001", "13200133002");
        String support = opsLogin("support", "support123");

        mvc().perform(post("/ops/after-sales/" + asNo + "/decide")
                        .header("Authorization", "Bearer " + support)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refund\":true,\"liability\":\"MERCHANT\",\"verdict\":\"照片可见破损，商家承担\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("REFUNDING"))
                .andExpect(jsonPath("$.data.liability").value("MERCHANT"));
    }

    @Test
    @DisplayName("维持商家决定时单子关闭 —— 驳回不是「什么都没发生」")
    void platformUpholdsMerchant() throws Exception {
        String asNo = escalated("13200133010", "13200133011");
        String support = opsLogin("support", "support123");

        mvc().perform(post("/ops/after-sales/" + asNo + "/decide")
                        .header("Authorization", "Bearer " + support)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refund\":false,\"liability\":\"USER\",\"verdict\":\"未提供有效证据\"}"))
                // USER 不在责任方取值域里（PLATFORM/MERCHANT/PICKUP）——先验它被拒
                .andExpect(jsonPath("$.code").value(10400));

        mvc().perform(post("/ops/after-sales/" + asNo + "/decide")
                        .header("Authorization", "Bearer " + support)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refund\":false,\"liability\":\"PLATFORM\",\"verdict\":\"规则如此，维持原判\"}"))
                .andExpect(jsonPath("$.data.status").value("CLOSED"));
    }

    @Test
    @DisplayName("★ 裁决必须写说明并落责任方 —— 口径未定不等于可以不记")
    void arbitrationNeedsVerdictAndLiability() throws Exception {
        String asNo = escalated("13200133020", "13200133021");
        String support = opsLogin("support", "support123");

        mvc().perform(post("/ops/after-sales/" + asNo + "/decide")
                        .header("Authorization", "Bearer " + support)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refund\":true,\"liability\":\"MERCHANT\",\"verdict\":\"  \"}"))
                .andExpect(jsonPath("$.code").value(10400));
        mvc().perform(post("/ops/after-sales/" + asNo + "/decide")
                        .header("Authorization", "Bearer " + support)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refund\":true,\"verdict\":\"没写责任方\"}"))
                .andExpect(jsonPath("$.code").value(10400));
    }

    @Test
    @DisplayName("★ 没上升到平台的单不能裁 —— 那等于替商家做了他还没做的决定")
    void cannotArbitrateBeforeEscalation() throws Exception {
        Ordered o = placeAndPay("13200133030", 6980L);
        String asNo = applyAfterSale(o, "REFUND_ONLY", "不想要了");
        String support = opsLogin("support", "support123");

        mvc().perform(post("/ops/after-sales/" + asNo + "/decide")
                        .header("Authorization", "Bearer " + support)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refund\":true,\"liability\":\"MERCHANT\",\"verdict\":\"抢着裁\"}"))
                .andExpect(jsonPath("$.code").value(20004));
    }

    @Test
    @DisplayName("极速退阈值：0 小时等于关掉功能却看着是开的，被拒")
    void fastRefundRuleGuards() throws Exception {
        String support = opsLogin("support", "support123");

        mvc().perform(get("/ops/after-sales/fast-refund-rule")
                        .header("Authorization", "Bearer " + support))
                .andExpect(jsonPath("$.code").value(0))
                // 默认关闭：自动退款的开关默认开着是件危险的事
                .andExpect(jsonPath("$.data.enabled").value(false));

        mvc().perform(post("/ops/after-sales/fast-refund-rule")
                        .header("Authorization", "Bearer " + support)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true,\"maxAmount\":2000,\"withinHours\":0,\"categories\":[]}"))
                .andExpect(jsonPath("$.code").value(10400));

        mvc().perform(post("/ops/after-sales/fast-refund-rule")
                        .header("Authorization", "Bearer " + support)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true,\"maxAmount\":5000,\"withinHours\":48,\"categories\":[]}"))
                .andExpect(jsonPath("$.code").value(0));
        mvc().perform(get("/ops/after-sales/fast-refund-rule")
                        .header("Authorization", "Bearer " + support))
                .andExpect(jsonPath("$.data.maxAmount").value(5000));
    }

    /** 走到 ARBITRATING：下单 → 申请售后 → 商家驳回 → 用户上升平台 */
    private String escalated(String buyerPhone, String ownerPhone) throws Exception {
        Ordered o = placeAndPay(buyerPhone, 6980L);
        String asNo = applyAfterSale(o, "RETURN_REFUND", "质量问题");
        String biz = loginAsOwnerOf("M0001", ownerPhone);
        mvc().perform(post("/biz/after-sale/" + asNo + "/reject").header("Authorization", "Bearer " + biz)
                .contentType(MediaType.APPLICATION_JSON).content("{\"remark\":\"商品无质量问题\"}"));
        mvc().perform(post("/mp/after-sale/" + asNo + "/escalate")
                        .header("Authorization", "Bearer " + o.userToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"appeal\":\"有照片为证\"}"))
                .andExpect(jsonPath("$.data.status").value("ARBITRATING"));
        return asNo;
    }

    @Test
    @DisplayName("退货退款：回填物流 → 商家确认收货 → 退款")
    void returnRefundFlow() throws Exception {
        Ordered o = placeAndPay("13200132015", 6980L);
        String asNo = applyAfterSale(o, "RETURN_REFUND", "不合适");
        String biz = loginAsOwnerOf("M0001", "13200132016");

        approve(biz, asNo);   // 退货退款：同意 ≠ 立刻退钱，要等收到货

        mvc().perform(post("/mp/after-sale/" + asNo + "/ship")
                        .header("Authorization", "Bearer " + o.userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expressCompany\":\"顺丰\",\"expressNo\":\"SF123\"}"))
                // 契约里叫 returnExpressNo（用户寄回的那张单）——
                // 后端此前发的是库列名 expressNo，端上照契约写就取不到
                .andExpect(jsonPath("$.data.returnExpressNo").value("SF123"));

        mvc().perform(post("/biz/after-sale/" + asNo + "/receive").header("Authorization", "Bearer " + biz))
                .andExpect(jsonPath("$.data.status").value("REFUNDED"));
    }

    @Test
    @DisplayName("用户可撤销申请；撤销后订单回到原状态")
    void userCanCancelApplication() throws Exception {
        Ordered o = placeAndPay("13200132017", 6980L);
        String asNo = applyAfterSale(o, "RETURN_REFUND", "手滑了");

        mvc().perform(post("/mp/after-sale/" + asNo + "/cancel")
                        .header("Authorization", "Bearer " + o.userToken))
                .andExpect(jsonPath("$.data.status").value("CLOSED"));

        // 撤销后还能再申请 —— 终态不锁死用户
        assertThat(applyAfterSale(o, "REFUND_ONLY", "还是要退")).isNotBlank();
    }

    // ---------------------------------------------------------------- 约束与越权

    @Test
    @DisplayName("同一子单同时只能有一个进行中的售后")
    void onlyOneActiveAfterSalePerSubOrder() throws Exception {
        Ordered o = placeAndPay("13200132020", 6980L);
        applyAfterSale(o, "RETURN_REFUND", "第一次");

        mvc().perform(post("/mp/order/" + o.subOrderNo + "/after-sale")
                        .header("Authorization", "Bearer " + o.userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"REFUND_ONLY\",\"reason\":\"第二次\"}"))
                .andExpect(jsonPath("$.code").value(10409));
    }

    @Test
    @DisplayName("★ 必填项缺失当场拒，不是让库抛「Field 'type' doesn't have a default value」")
    void missingRequiredFieldsAreRejectedUpFront() throws Exception {
        Ordered o = placeAndPay("13200132031", 6980L);

        // 不传 type：库上 type NOT NULL，落到 insert 会被包成通用 500「系统开小差了」，
        // 而真正的问题是少传了一个字段 —— 报错与实际问题无关，排查的人只能去翻服务器日志
        mvc().perform(post("/mp/order/" + o.subOrderNo + "/after-sale")
                        .header("Authorization", "Bearer " + o.userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"包装破了\"}"))
                .andExpect(jsonPath("$.code").value(10400));

        // 不认识的 type 同样拒：写进去之后，处理流程会按一个不存在的分支走
        mvc().perform(post("/mp/order/" + o.subOrderNo + "/after-sale")
                        .header("Authorization", "Bearer " + o.userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"WHATEVER\",\"reason\":\"包装破了\"}"))
                .andExpect(jsonPath("$.code").value(10400));

        // 原因为空：商家收到一张没写原因的售后单，只能打电话问
        mvc().perform(post("/mp/order/" + o.subOrderNo + "/after-sale")
                        .header("Authorization", "Bearer " + o.userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"REFUND_ONLY\",\"reason\":\"  \"}"))
                .andExpect(jsonPath("$.code").value(10400));
    }

    @Test
    @DisplayName("退款金额不能超过子单实付")
    void refundCannotExceedPaid() throws Exception {
        Ordered o = placeAndPay("13200132021", 6980L);
        mvc().perform(post("/mp/order/" + o.subOrderNo + "/after-sale")
                        .header("Authorization", "Bearer " + o.userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"REFUND_ONLY\",\"reason\":\"多退点\",\"refundMinor\":999999}"))
                .andExpect(jsonPath("$.code").value(10400));
    }

    @Test
    @DisplayName("未支付的订单不能申请售后")
    void unpaidOrderCannotApply() throws Exception {
        String token = login("13200132022");
        String subOrderNo = placeOnly(token, "m5-unpaid");

        mvc().perform(post("/mp/order/" + subOrderNo + "/after-sale")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"REFUND_ONLY\",\"reason\":\"没付款也想退\"}"))
                .andExpect(jsonPath("$.code").value(20004));
    }

    @Test
    @DisplayName("越权：别人的售后单看不到、也处理不了")
    void cannotTouchOthersAfterSale() throws Exception {
        Ordered o = placeAndPay("13200132023", 6980L);
        String asNo = applyAfterSale(o, "REFUND_ONLY", "我的单");

        String stranger = login("13200132024");
        mvc().perform(get("/mp/after-sale/" + asNo).header("Authorization", "Bearer " + stranger))
                .andExpect(jsonPath("$.code").value(10404));

        // 别家商家也处理不了
        String otherBiz = loginAsOwnerOf("M0002", "13200132025");
        mvc().perform(post("/biz/after-sale/" + asNo + "/approve").header("Authorization", "Bearer " + otherBiz))
                .andExpect(jsonPath("$.code").value(10404));
    }

    @Test
    @DisplayName("商家只看得到自己商品的售后")
    void merchantSeesOwnAfterSaleOnly() throws Exception {
        Ordered a = placeAndPay("13200132026", 6980L);          // M0001
        applyAfterSale(a, "REFUND_ONLY", "M0001 的单");

        String biz = loginAsOwnerOf("M0001", "13200132027");
        String body = mvc().perform(get("/biz/after-sale").header("Authorization", "Bearer " + biz))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        for (JsonNode row : json.readTree(body).get("data")) {
            assertThat(row.get("subOrderNo").asString()).isNotBlank();
        }
    }

    @Test
    @DisplayName("售后原因字典可取（游客也能看，帮助页要用）")
    void reasonsDictionary() throws Exception {
        mvc().perform(get("/mp/after-sale/reasons"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(org.hamcrest.Matchers.greaterThan(0)));
    }

    @Test
    @DisplayName("我的售后列表按时间倒序")
    void myAfterSaleList() throws Exception {
        Ordered o = placeAndPay("13200132028", 6980L);
        applyAfterSale(o, "REFUND_ONLY", "列表用");

        mvc().perform(get("/mp/after-sale").header("Authorization", "Bearer " + o.userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(org.hamcrest.Matchers.greaterThan(0)));
    }

    // ---------------------------------------------------------------- helpers

    private record Ordered(String userToken, String subOrderNo, String payOrderNo) {
    }

    /** 先把结算单推到已分账，回退才有意义（没分过账的单不会向通道发回退指令）。 */
    private void prepareSplit(String subOrderNo) {
        settleService.merchantBills("M0001", java.util.List.of()).stream()
                .filter(b -> b.subOrderNo().equals(subOrderNo))
                .findFirst()
                .ifPresent(b -> settleService.executeSplit(b.settleNo()));
    }

    private String opsLogin(String username, String password) throws Exception {
        String body = mvc().perform(post("/ops/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("token").asString();
    }

    private String detail(String token, String afterSaleNo) throws Exception {
        return mvc().perform(get("/mp/after-sale/" + afterSaleNo).header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    @DisplayName("★★★ 退货退款要把货加回库存（V256）—— 此前这条路径从来没有实现过")
    void returnRefundRestoresStock() throws Exception {
        int before = stockOf("SK0003");

        Ordered o = placeAndPay("13200132080", 6980L);
        assertThat(stockOf("SK0003")).as("支付成功先扣掉").isEqualTo(before - 1);

        String asNo = applyAfterSale(o, "RETURN_REFUND", "不合适");
        String biz = loginAsOwnerOf("M0001", "13200132081");
        approve(biz, asNo);
        mvc().perform(post("/mp/after-sale/" + asNo + "/ship")
                .header("Authorization", "Bearer " + o.userToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expressCompany\":\"顺丰\",\"expressNo\":\"SF-RS-1\"}"));
        mvc().perform(post("/biz/after-sale/" + asNo + "/receive")
                        .header("Authorization", "Bearer " + biz))
                .andExpect(jsonPath("$.data.status").value("REFUNDED"));

        /*
         * 货回到店里了，库存必须回到下单前。
         *
         * 不补的话库里当它卖掉了 —— 这一件会被再卖一次，而且要等到发货那天才发现。
         * 撤掉 AfterSaleServiceImpl.restoreStockIfReturned 这一条就红。
         */
        assertThat(stockOf("SK0003")).as("退货入库后应回到下单前").isEqualTo(before);
    }

    @Test
    @DisplayName("★★★ 仅退款**不**回补库存 —— 货根本没回来，补了就是凭空多出几件")
    void refundOnlyDoesNotRestoreStock() throws Exception {
        int before = stockOf("SK0003");

        Ordered o = placeAndPay("13200132082", 6980L);
        String asNo = applyAfterSale(o, "REFUND_ONLY", "不想要了");
        approve(loginAsOwnerOf("M0001", "13200132083"), asNo);

        assertThat(stockOf("SK0003")).as("仅退款不回补").isEqualTo(before - 1);
    }

    /** 直接读库里的实存 —— 这一条断言不该经过任何会顺手改数的接口。 */
    private int stockOf(String skuNo) {
        return DataScopeContext.executeWithoutScope(() ->
                skuMapper.selectList(Wrappers.<PrdSku>lambdaQuery()
                                .eq(PrdSku::getSkuNo, skuNo))
                        .stream().mapToInt(PrdSku::getStock).max().orElse(0));
    }

    private void approve(String bizToken, String afterSaleNo) throws Exception {
        mvc().perform(post("/biz/after-sale/" + afterSaleNo + "/approve")
                        .header("Authorization", "Bearer " + bizToken))
                .andExpect(status().isOk());
    }

    private String applyAfterSale(Ordered o, String type, String reason) throws Exception {
        String body = mvc().perform(post("/mp/order/" + o.subOrderNo + "/after-sale")
                        .header("Authorization", "Bearer " + o.userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"" + type + "\",\"reason\":\"" + reason
                                + "\",\"images\":[\"https://cdn/x.jpg\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("afterSaleNo").asString();
    }

    /** 下单但不支付，返回子单号。 */
    private String placeOnly(String token, String idemKey) throws Exception {
        mvc().perform(post("/mp/cart/add").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"goodsNo\":\"G0002\",\"skuNo\":\"SK0003\",\"qty\":1}"));
        mvc().perform(post("/mp/order").header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", idemKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fulfillment\":\"STORE_PICKUP\",\"pickupNo\":\"PP0001\"}"));
        String list = mvc().perform(get("/mp/order").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(list).get("data").get("records").get(0).get("orderNo").asString();
    }

    /**
     * 下单 + 支付。
     *
     * @param amountHint 3980（M0002 的蓝莓，**低于极速退阈值 5000**）或 6980（M0001 的油，高于阈值）——
     *                   用金额挑商品是为了让用例读起来就知道走的是哪条分支
     */
    private Ordered placeAndPay(String phone, long amountHint) throws Exception {
        String token = login(phone);
        String goods = amountHint <= 4000L ? "G0004" : "G0002";
        String sku = amountHint <= 4000L ? "SK0005" : "SK0003";

        mvc().perform(post("/mp/cart/add").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"goodsNo\":\"" + goods + "\",\"skuNo\":\"" + sku + "\",\"qty\":1}"));
        String body = mvc().perform(post("/mp/order").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "m5-" + phone)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fulfillment\":\"STORE_PICKUP\",\"pickupNo\":\"PP0001\"}"))
                .andReturn().getResponse().getContentAsString();
        String payOrderNo = json.readTree(body).get("data").get("payOrderNo").asString();

        mvc().perform(post("/pay/callback/stub").contentType(MediaType.APPLICATION_JSON)
                .content("{\"outTradeNo\":\"" + payOrderNo + "\",\"transactionId\":\"TX-m5-" + phone
                        + "\",\"sign\":\"" + STUB_SECRET + "\"}"));

        String list = mvc().perform(get("/mp/order").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        String subOrderNo = json.readTree(list).get("data").get("records").get(0).get("orderNo").asString();
        return new Ordered(token, subOrderNo, payOrderNo);
    }

    private String loginAsOwnerOf(String merchantNo, String phone) throws Exception {
        String token = login(phone);
        String body = mvc().perform(get("/mp/user/profile").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        String userNo = json.readTree(body).get("data").get("userNo").asString();

        MchEntity m = merchantMapper.selectOne(Wrappers.<MchEntity>lambdaQuery()
                .eq(MchEntity::getEntityNo, merchantNo).last("limit 1"));
        m.setOwnerUserNo(userNo);
        // V44 起 B 端身份来自 mch_account，不再是 owner_user_no —— 两处都要写
        grantOwner(m.getEntityNo(), userNo);
        merchantMapper.updateById(m);
        // A7：这个令牌是拿去打 /biz/** 的，必须是 btk_
        return TestLogin.merchantOwner(mvc(), json, otpStore, phone);
    }

    private String login(String phone) throws Exception {
        return TestLogin.consumer(mvc(), json, otpStore, phone);
    }
    /** 授予 B 端身份：写一条 owner 成员行（幂等）。 */
    private void grantOwner(String merchantNo, String userNo) {
        var existing = merchantStaffMapper.selectOne(
                com.baomidou.mybatisplus.core.toolkit.Wrappers
                        .<ai.neargo.shop.merchant.entity.MchAccount>lambdaQuery()
                        .eq(ai.neargo.shop.merchant.entity.MchAccount::getEntityNo, merchantNo)
                        .last("limit 1"));
        if (existing != null) {
            existing.setUserNo(userNo);
            merchantStaffMapper.updateById(existing);
            return;
        }
        var st = new ai.neargo.shop.merchant.entity.MchAccount();
        st.setMchAccountNo("SF-T-" + merchantNo);
        st.setEntityNo(merchantNo);
        st.setUserNo(userNo);
        st.setIsOwner(true);
        st.setIsPrimary(true);
        st.setStatus(ai.neargo.shop.merchant.entity.MchAccount.ACTIVE);
        merchantStaffMapper.insert(st);
    }

    @Autowired
    private ai.neargo.shop.merchant.mapper.MerchantMappers.MchAccountMapper merchantStaffMapper;

}
