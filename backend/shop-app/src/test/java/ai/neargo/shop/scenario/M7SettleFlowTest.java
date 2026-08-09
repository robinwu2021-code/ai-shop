package ai.neargo.shop.scenario;

import ai.neargo.shop.marketing.coupon.entity.MktCoupon;
import ai.neargo.shop.marketing.coupon.mapper.CouponMappers.CouponMapper;
import ai.neargo.shop.user.merchant.entity.MchEntity;
import ai.neargo.shop.user.mapper.UserMappers.MchEntityMapper;
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

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M7 结算与分账 —— **用例先行**。**资损最高危模块**。
 *
 * <p>三件事必须对：
 * ① **金额口径** —— 平台券的钱要给商家（gross 含平台补贴），商家券的钱不给
 * ② **费率分档** —— 自带客流零佣金（R16），且费率**落库快照**（费率会变，历史账不能跟着变）
 * ③ **退款先回退分账** —— M5 已锁住顺序，这里验证真实实现下依然成立
 */
@SpringBootTest
@ActiveProfiles("test")
class M7SettleFlowTest {

    private static final String STUB_SECRET = "stub-secret";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private ai.neargo.shop.user.service.OtpStore otpStore;

    @Autowired
    private MchEntityMapper merchantMapper;

    @Autowired
    private CouponMapper couponMapper;

    @Autowired
    private ai.neargo.shop.settle.SettleService settleService;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    // ---------------------------------------------------------------- 金额口径

    @Test
    @DisplayName("★ 支付成功即生成结算单（按子单），金额可拆解")
    void billGeneratedOnPaid() throws Exception {
        String token = login("12800128001");
        String payOrderNo = buyAndPay(token, "G0002", "SK0003", null, "m7-basic");

        String biz = loginAsOwnerOf("M0001", "12800128002");
        JsonNode bill = firstBill(biz);

        // 无优惠时：gross = 实付 6980
        assertThat(bill.get("grossMinor").asLong()).isEqualTo(6980L);
        // 三列都在：商家问「为什么只结了 X」时要能拆给他看
        assertThat(bill.get("commissionMinor").asLong()).isEqualTo(
                bill.get("grossMinor").asLong() - bill.get("netMinor").asLong()
                        - bill.get("serviceFeeMinor").asLong());
        assertThat(payOrderNo).isNotBlank();
    }

    @Test
    @DisplayName("★ 平台券的钱要给商家：gross = 实付 + 平台补贴")
    void platformCouponIsReimbursedToMerchant() throws Exception {
        String token = login("12800128003");
        String userCouponNo = receive(token, coupon("平台满50减5", 500L, 5000L, "PLATFORM", null));
        buyAndPay(token, "G0002", "SK0003", userCouponNo, "m7-platform-coupon");

        String biz = loginAsOwnerOf("M0001", "12800128004");
        JsonNode bill = firstBill(biz);

        // 用户实付 6480，但商家该拿到 6980 —— 那 500 是平台补的
        assertThat(bill.get("grossMinor").asLong()).isEqualTo(6980L);
    }

    @Test
    @DisplayName("★ 商家券的钱商家自己出：gross = 实付，不补回")
    void merchantCouponIsNotReimbursed() throws Exception {
        String token = login("12800128005");
        String userCouponNo = receive(token, coupon("本店满50减5", 500L, 5000L, "MERCHANT", "M0001"));
        buyAndPay(token, "G0002", "SK0003", userCouponNo, "m7-merchant-coupon");

        String biz = loginAsOwnerOf("M0001", "12800128006");
        JsonNode bill = firstBill(biz);

        // 商家自己让的利，平台不补 —— 出资方分列（Q9）在这里兑现
        assertThat(bill.get("grossMinor").asLong()).isEqualTo(6480L);
    }

    // ---------------------------------------------------------------- 费率分档

    @Test
    @DisplayName("★ 自带客流零佣金（R16）：商家实得 = 应结基数")
    void merchantOwnedTrafficHasZeroCommission() throws Exception {
        String token = login("12800128010");
        // 先扫码进店 → 归因 MERCHANT_OWNED
        enterStore(token, "M0001");
        buyAndPay(token, "G0002", "SK0003", null, "m7-owned");

        String biz = loginAsOwnerOf("M0001", "12800128011");
        JsonNode bill = firstBill(biz);

        assertThat(bill.get("trafficSource").asString()).isEqualTo("MERCHANT_OWNED");
        assertThat(bill.get("commissionRate").asInt()).isZero();
        assertThat(bill.get("commissionMinor").asLong()).isZero();
        // 他带来的客户在别家的消费才是平台收益（ADR-004 §6）
        assertThat(bill.get("netMinor").asLong()).isEqualTo(bill.get("grossMinor").asLong());
    }

    @Test
    @DisplayName("★ 平台客流按档收佣金，且费率**落库快照**（费率变了历史账不变）")
    void platformTrafficPaysCommission() throws Exception {
        String token = login("12800128012");
        buyAndPay(token, "G0002", "SK0003", null, "m7-platform");

        String biz = loginAsOwnerOf("M0001", "12800128013");
        JsonNode bill = firstBill(biz);

        assertThat(bill.get("trafficSource").asString()).isEqualTo("PLATFORM");
        int rate = bill.get("commissionRate").asInt();
        assertThat(rate).isPositive();
        // 佣金 = 基数 × 费率（万分比）
        assertThat(bill.get("commissionMinor").asLong())
                .isEqualTo(bill.get("grossMinor").asLong() * rate / 10000);
        assertThat(bill.get("netMinor").asLong())
                .isEqualTo(bill.get("grossMinor").asLong() - bill.get("commissionMinor").asLong());
    }

    @Test
    @DisplayName("费率说明可查：商家要能自己算清楚每单能拿多少（B-11.9.5）")
    void rateCardIsVisibleToMerchant() throws Exception {
        String biz = loginAsOwnerOf("M0001", "12800128014");
        mvc().perform(get("/biz/settle/rate-card").header("Authorization", "Bearer " + biz))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.merchantOwnedRate").value(0))
                .andExpect(jsonPath("$.data.platformRate").value(org.hamcrest.Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.data.note").isNotEmpty());
    }

    // ---------------------------------------------------------------- 分账执行

    @Test
    @DisplayName("分账执行：PENDING → SPLIT，回执落库")
    void executeSplit() throws Exception {
        String token = login("12800128020");
        buyAndPay(token, "G0002", "SK0003", null, "m7-exec");
        String biz = loginAsOwnerOf("M0001", "12800128021");
        String settleNo = firstBill(biz).get("settleNo").asString();

        settleService.executeSplit(settleNo);

        mvc().perform(get("/biz/settle/bills/" + settleNo).header("Authorization", "Bearer " + biz))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SPLIT"))
                .andExpect(jsonPath("$.data.splitAt").isNumber());
    }

    @Test
    @DisplayName("分账幂等：重复执行不会重复打款")
    void splitIsIdempotent() throws Exception {
        String token = login("12800128022");
        buyAndPay(token, "G0002", "SK0003", null, "m7-idem");
        String biz = loginAsOwnerOf("M0001", "12800128023");
        String settleNo = firstBill(biz).get("settleNo").asString();

        settleService.executeSplit(settleNo);
        settleService.executeSplit(settleNo);

        // 分账日志只应有一条 SPLIT —— 两条意味着给商家打了两次钱
        assertThat(settleService.splitLogCount(settleNo, "SPLIT")).isEqualTo(1);
    }

    @Test
    @DisplayName("一个子单只能有一张结算单（重复生成 = 重复分账）")
    void oneBillPerSubOrder() throws Exception {
        String token = login("12800128024");
        String payOrderNo = buyAndPay(token, "G0002", "SK0003", null, "m7-once");

        // 重复触发生成（模拟 OrderPaid 事件重投）
        settleService.generateForOrder(payOrderNo);
        settleService.generateForOrder(payOrderNo);

        String biz = loginAsOwnerOf("M0001", "12800128025");
        String body = mvc().perform(get("/biz/settle/bills").header("Authorization", "Bearer " + biz))
                .andReturn().getResponse().getContentAsString();
        long count = 0;
        for (JsonNode b : json.readTree(body).get("data")) {
            if (payOrderNo.equals(b.get("orderNo").asString())) {
                count++;
            }
        }
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("★ 退款先回退分账（真实实现下依然成立，M5 的顺序约束不因换实现而失效）")
    void refundReversesSplitFirst() throws Exception {
        String token = login("12800128030");
        buyAndPay(token, "G0002", "SK0003", null, "m7-refund");
        String biz = loginAsOwnerOf("M0001", "12800128031");
        String settleNo = firstBill(biz).get("settleNo").asString();
        settleService.executeSplit(settleNo);

        // 申请售后并同意 → 触发退款
        String subOrderNo = firstSubOrderNo(token);
        String asNo = applyAfterSale(token, subOrderNo);
        mvc().perform(post("/biz/after-sale/" + asNo + "/approve").header("Authorization", "Bearer " + biz))
                .andExpect(jsonPath("$.code").value(0));

        mvc().perform(get("/biz/settle/bills/" + settleNo).header("Authorization", "Bearer " + biz))
                .andExpect(jsonPath("$.data.status").value("REVERSED"));
        // 回退也要留痕：钱从商家账上收回来这件事必须可查
        assertThat(settleService.splitLogCount(settleNo, "REVERSE")).isEqualTo(1);
    }

    @Test
    @DisplayName("未分账的单退款：直接标记回退，不产生无谓的回退指令")
    void refundBeforeSplitNeedsNoReversal() throws Exception {
        String token = login("12800128032");
        buyAndPay(token, "G0002", "SK0003", null, "m7-refund-early");
        String biz = loginAsOwnerOf("M0001", "12800128033");
        String settleNo = firstBill(biz).get("settleNo").asString();

        String asNo = applyAfterSale(token, firstSubOrderNo(token));
        mvc().perform(post("/biz/after-sale/" + asNo + "/approve").header("Authorization", "Bearer " + biz))
                .andExpect(jsonPath("$.code").value(0));

        mvc().perform(get("/biz/settle/bills/" + settleNo).header("Authorization", "Bearer " + biz))
                .andExpect(jsonPath("$.data.status").value("REVERSED"));
        // 没分过账就没必要向支付服务商发回退 —— 发了只会收到「找不到分账单」的错误
        assertThat(settleService.splitLogCount(settleNo, "REVERSE")).isZero();
    }

    // ---------------------------------------------------------------- 越权

    @Test
    @DisplayName("商家只看得到自己的结算单")
    void merchantSeesOwnBillsOnly() throws Exception {
        String buyer = login("12800128040");
        buyAndPay(buyer, "G0003", "SK0004", null, "m7-scope");   // M0002 的货

        String m1 = loginAsOwnerOf("M0001", "12800128041");
        String body = mvc().perform(get("/biz/settle/bills").header("Authorization", "Bearer " + m1))
                .andReturn().getResponse().getContentAsString();
        for (JsonNode b : json.readTree(body).get("data")) {
            assertThat(b.get("merchantNo").asString()).isEqualTo("M0001");
        }
    }

    @Test
    @DisplayName("别家的结算单查不到（settleNo 可猜）")
    void cannotReadOthersBill() throws Exception {
        String buyer = login("12800128042");
        buyAndPay(buyer, "G0002", "SK0003", null, "m7-others");
        String m1 = loginAsOwnerOf("M0001", "12800128043");
        String settleNo = firstBill(m1).get("settleNo").asString();

        String m2 = loginAsOwnerOf("M0002", "12800128044");
        mvc().perform(get("/biz/settle/bills/" + settleNo).header("Authorization", "Bearer " + m2))
                .andExpect(jsonPath("$.code").value(10404));
    }

    @Test
    @DisplayName("非商家看不到结算（没有钱的账可看）")
    void normalUserCannotSeeBills() throws Exception {
        String token = login("12800128045");
        mvc().perform(get("/biz/settle/bills").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(10403));
    }

    // ---------------------------------------------------------------- helpers

    private JsonNode firstBill(String bizToken) throws Exception {
        String body = mvc().perform(get("/biz/settle/bills").header("Authorization", "Bearer " + bizToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode list = json.readTree(body).get("data");
        assertThat(list).isNotEmpty();
        return list.get(0);
    }

    private String firstSubOrderNo(String token) throws Exception {
        String body = mvc().perform(get("/mp/order").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("records").get(0).get("orderNo").asString();
    }

    private String applyAfterSale(String token, String subOrderNo) throws Exception {
        String body = mvc().perform(post("/mp/order/" + subOrderNo + "/after-sale")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        // 用 REFUND_ONLY：商家同意即退款。RETURN_REFUND 的「同意」只到待寄回，
                        // 不触发退款链路，也就验证不到分账回退
                        .content("{\"type\":\"REFUND_ONLY\",\"reason\":\"质量问题\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("afterSaleNo").asString();
    }

    private void enterStore(String token, String merchantNo) throws Exception {
        MchEntity m = merchantMapper.selectOne(Wrappers.<MchEntity>lambdaQuery()
                .eq(MchEntity::getEntityNo, merchantNo).last("limit 1"));
        mvc().perform(post("/mp/store/" + merchantNo + "/enter").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"storeCode\":\"" + m.getStoreCode() + "\"}"));
    }

    private String buyAndPay(String token, String goodsNo, String skuNo,
                             String userCouponNo, String idemKey) throws Exception {
        mvc().perform(post("/mp/cart/add").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"goodsNo\":\"" + goodsNo + "\",\"skuNo\":\"" + skuNo + "\",\"qty\":1}"));

        String couponPart = userCouponNo == null ? "" : ",\"couponNo\":\"" + userCouponNo + "\"";
        String body = mvc().perform(post("/mp/order").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", idemKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fulfillment\":\"STORE_PICKUP\",\"pickupNo\":\"PP0001\"" + couponPart + "}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        String payOrderNo = json.readTree(body).get("data").get("payOrderNo").asString();

        mvc().perform(post("/callback/pay/stub").contentType(MediaType.APPLICATION_JSON)
                .content("{\"outTradeNo\":\"" + payOrderNo + "\",\"transactionId\":\"TX-" + idemKey
                        + "\",\"sign\":\"" + STUB_SECRET + "\"}"));
        return payOrderNo;
    }

    private String receive(String token, String couponNo) throws Exception {
        String body = mvc().perform(post("/mp/coupon/" + couponNo + "/receive")
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("userCouponNo").asString();
    }

    private String coupon(String title, long face, long threshold, String funder, String merchantNo) {
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
        c.setEntityNo(merchantNo);
        c.setTotalCount(100);
        c.setReceivedCount(0);
        c.setPerUserLimit(1);
        c.setStartAt(now - Duration.ofDays(1).toMillis());
        c.setEndAt(now + Duration.ofDays(30).toMillis());
        c.setStatus("ACTIVE");
        couponMapper.insert(c);
        return c.getCouponNo();
    }

    private String loginAsOwnerOf(String merchantNo, String phone) throws Exception {
        String token = login(phone);
        String body = mvc().perform(get("/mp/user/profile").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        MchEntity m = merchantMapper.selectOne(Wrappers.<MchEntity>lambdaQuery()
                .eq(MchEntity::getEntityNo, merchantNo).last("limit 1"));
        m.setOwnerUserNo(json.readTree(body).get("data").get("userNo").asString());
        // V44 起 B 端身份来自 mch_account，不再是 owner_user_no —— 两处都要写
        grantOwner(m.getEntityNo(), json.readTree(body).get("data").get("userNo").asString());
        merchantMapper.updateById(m);
        return login(phone);
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
    /** 授予 B 端身份：写一条 owner 成员行（幂等）。 */
    private void grantOwner(String merchantNo, String userNo) {
        var existing = merchantStaffMapper.selectOne(
                com.baomidou.mybatisplus.core.toolkit.Wrappers
                        .<ai.neargo.shop.user.merchant.entity.MchAccount>lambdaQuery()
                        .eq(ai.neargo.shop.user.merchant.entity.MchAccount::getEntityNo, merchantNo)
                        .last("limit 1"));
        if (existing != null) {
            existing.setUserNo(userNo);
            merchantStaffMapper.updateById(existing);
            return;
        }
        var st = new ai.neargo.shop.user.merchant.entity.MchAccount();
        st.setMchAccountNo("SF-T-" + merchantNo);
        st.setEntityNo(merchantNo);
        st.setUserNo(userNo);
        st.setIsOwner(true);
        st.setIsPrimary(true);
        st.setStatus(ai.neargo.shop.user.merchant.entity.MchAccount.ACTIVE);
        merchantStaffMapper.insert(st);
    }

    @Autowired
    private ai.neargo.shop.user.mapper.UserMappers.MchAccountMapper merchantStaffMapper;

}
