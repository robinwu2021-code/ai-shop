package ai.neargo.shop.scenario;

import ai.neargo.shop.merchant.entity.MchEntity;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchEntityMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * B 端经营通知（TDD-通知与消息推送 §二期）。
 *
 * <p>走真实链路：C 支付/售后/评价 → 业务域发事件 → Outbox 投递 →
 * NotificationConsumer 按门店×角色解析受众 → 员工的 STAFF 收件箱。
 *
 * <p>核心断言除了「收到」，还有**收件箱隔离**：B 端与 C 端共用账号池，
 * 同一个人既是买家又是店主时，「新订单」只能出现在 /biz/message，
 * 「支付成功」只能出现在 /mp/message —— 混了的话角标会互相污染。
 */
@SpringBootTest
@ActiveProfiles("test")
class BizNotifyFlowTest {

    private static final String STUB_SECRET = "stub-secret";

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;
    @Autowired
    private ai.neargo.shop.event.OutboxDispatcher dispatcher;
    @Autowired
    private MchEntityMapper merchantMapper;
    @Autowired
    private ai.neargo.shop.merchant.mapper.MerchantMappers.MchAccountMapper merchantStaffMapper;
    @Autowired
    private ai.neargo.shop.product.mapper.ProductMappers.SkuMapper skuMapper;

    @BeforeEach
    void drainOutbox() {
        for (int i = 0; i < 50 && dispatcher.pendingCount() > 0; i++) {
            dispatcher.dispatchPending();
        }
    }

    @Autowired
    private ai.neargo.shop.community.mapper.CommunityMappers.PickupPointMapper pickupMapper;
    @Autowired
    private ai.neargo.shop.merchant.mapper.MerchantMappers.MchStoreMapper storeMapper;

    /** 种子复位，理由见 WxNotifyFlowTest#healPickupSeed。 */
    @BeforeEach
    void healPickupSeed() {
        var p = pickupMapper.selectOne(Wrappers
                .<ai.neargo.shop.community.entity.CmtPickupPoint>lambdaQuery()
                .eq(ai.neargo.shop.community.entity.CmtPickupPoint::getPickupNo, "PP0001")
                .last("limit 1"));
        if (p != null && (!"ACTIVE".equals(p.getStatus()) || !"ST-M0001".equals(p.getOwnerRef()))) {
            p.setStatus("ACTIVE");
            p.setOwnerRef("ST-M0001");
            p.setType("STORE");
            pickupMapper.updateById(p);
        }
        var s = storeMapper.selectOne(Wrappers
                .<ai.neargo.shop.merchant.entity.MchStore>lambdaQuery()
                .eq(ai.neargo.shop.merchant.entity.MchStore::getStoreNo, "ST-M0001")
                .last("limit 1"));
        if (s != null && !ai.neargo.shop.merchant.entity.MchStore.ACTIVE.equals(s.getStatus())) {
            s.setStatus(ai.neargo.shop.merchant.entity.MchStore.ACTIVE);
            storeMapper.updateById(s);
        }
    }

    @BeforeEach
    void refillStock() {
        var sku = skuMapper.selectOne(Wrappers
                .<ai.neargo.shop.product.entity.PrdSku>lambdaQuery()
                .eq(ai.neargo.shop.product.entity.PrdSku::getSkuNo, "SK0003").last("limit 1"));
        if (sku != null && sku.getStock() < 50) {
            sku.setStock(500);
            skuMapper.updateById(sku);
        }
    }

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup
                        .SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    @DisplayName("★ 支付成功 → 店主的 B 端收件箱收到「新订单」；C 端收件箱不被污染")
    void paidOrderNotifiesOwner() throws Exception {
        String buyer = ai.neargo.shop.support.TestLogin.consumer(mvc(), json, otpStore, "12700127201");
        String owner = loginAsOwnerOf("M0001", "12700127202");

        buyAndPay(buyer, "bizn-paid");
        dispatcher.dispatchPending();

        // B 端收件箱：新订单在这
        assertThat(find(bizMessages(owner), "新订单")).isNotNull();
        // 收件箱隔离：店主本人的 C 端收件箱不该出现「新订单」
        assertThat(find(mpMessages(owner), "新订单")).isNull();
        // 买家的 C 端收件箱只有「支付成功」
        assertThat(find(mpMessages(buyer), "支付成功")).isNotNull();
        assertThat(find(mpMessages(buyer), "新订单")).isNull();
    }

    @Test
    @DisplayName("★ unread-count 轮询口径：来单 +1，全部已读归零")
    void unreadCountTracksInbox() throws Exception {
        String buyer = ai.neargo.shop.support.TestLogin.consumer(mvc(), json, otpStore, "12700127203");
        String owner = loginAsOwnerOf("M0001", "12700127204");

        long before = unreadCount(owner);
        buyAndPay(buyer, "bizn-unread");
        dispatcher.dispatchPending();

        assertThat(unreadCount(owner)).isGreaterThan(before);

        mvc().perform(post("/biz/message/read-all").header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk());
        assertThat(unreadCount(owner)).isZero();
    }

    @Test
    @DisplayName("★ 售后申请 → 店主收到「新的售后申请」")
    void afterSaleNotifiesOwner() throws Exception {
        String buyer = ai.neargo.shop.support.TestLogin.consumer(mvc(), json, otpStore, "12700127205");
        String owner = loginAsOwnerOf("M0001", "12700127206");

        buyAndPay(buyer, "bizn-as");
        dispatcher.dispatchPending();
        String subOrderNo = latestSubOrderNo(buyer);

        mvc().perform(post("/mp/order/" + subOrderNo + "/after-sale")
                        .header("Authorization", "Bearer " + buyer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"REFUND_ONLY\",\"reason\":\"不想要了\"}"))
                .andExpect(status().isOk());
        dispatcher.dispatchPending();

        assertThat(find(bizMessages(owner), "售后")).isNotNull();
    }

    @Test
    @DisplayName("★ 差评（≤2 星）→ 店主收到点名的「收到差评」")
    void badReviewNotifiesOwnerByName() throws Exception {
        String buyer = ai.neargo.shop.support.TestLogin.consumer(mvc(), json, otpStore, "12700127207");
        String owner = loginAsOwnerOf("M0001", "12700127208");

        buyAndPay(buyer, "bizn-review");
        dispatcher.dispatchPending();
        String subOrderNo = latestSubOrderNo(buyer);

        // 核销完成（评价开放的前提）。**先到货再核销** —— 货没到点上时核销会被拒
        mvc().perform(post("/biz/pickup/arrived").header("Authorization", "Bearer " + owner)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderNos\":[\"" + subOrderNo + "\"]}"));
        String verifyCode = verifyCodeOf(buyer);
        mvc().perform(post("/biz/pickup/verify").header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"verifyCode\":\"" + verifyCode + "\"}"))
                .andExpect(status().isOk());
        dispatcher.dispatchPending();

        mvc().perform(post("/mp/review").header("Authorization", "Bearer " + buyer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderNo\":\"" + subOrderNo
                                + "\",\"goodsNo\":\"G0002\",\"rating\":1,\"content\":\"货不对板\"}"))
                .andExpect(status().isOk());
        dispatcher.dispatchPending();

        JsonNode bad = find(bizMessages(owner), "收到差评");
        assertThat(bad).isNotNull();
        assertThat(bad.get("body").asString()).contains("1 星");
    }

    // ---------------------------------------------------------------- helpers

    private JsonNode bizMessages(String token) throws Exception {
        String body = mvc().perform(get("/biz/message").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data");
    }

    private JsonNode mpMessages(String token) throws Exception {
        String body = mvc().perform(get("/mp/message").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data");
    }

    private long unreadCount(String token) throws Exception {
        String body = mvc().perform(get("/biz/message/unread-count")
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").asLong();
    }

    private JsonNode find(JsonNode messages, String titlePart) {
        for (JsonNode m : messages) {
            if (m.get("title").asString().contains(titlePart)) {
                return m;
            }
        }
        return null;
    }

    private String latestSubOrderNo(String token) throws Exception {
        String body = mvc().perform(get("/mp/order").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("records").get(0).get("orderNo").asString();
    }

    private String verifyCodeOf(String token) throws Exception {
        String body = mvc().perform(get("/mp/order").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("records").get(0).get("verifyCode").asString();
    }

    private void buyAndPay(String token, String idemKey) throws Exception {
        mvc().perform(post("/mp/cart/add").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"goodsNo\":\"G0002\",\"skuNo\":\"SK0003\",\"qty\":1}"));
        String body = mvc().perform(post("/mp/order").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", idemKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fulfillment\":\"STORE_PICKUP\",\"pickupNo\":\"PP0001\"}"))
                .andReturn().getResponse().getContentAsString();
        String payOrderNo = json.readTree(body).get("data").get("payOrderNo").asString();
        mvc().perform(post("/callback/pay/stub").contentType(MediaType.APPLICATION_JSON)
                .content("{\"outTradeNo\":\"" + payOrderNo + "\",\"transactionId\":\"TX-" + idemKey
                        + "\",\"sign\":\"" + STUB_SECRET + "\"}"));
    }

    private String loginAsOwnerOf(String merchantNo, String phone) throws Exception {
        String token = ai.neargo.shop.support.TestLogin.consumer(mvc(), json, otpStore, phone);
        String body = mvc().perform(get("/mp/user/profile").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        String userNo = json.readTree(body).get("data").get("userNo").asString();
        MchEntity m = merchantMapper.selectOne(Wrappers.<MchEntity>lambdaQuery()
                .eq(MchEntity::getEntityNo, merchantNo).last("limit 1"));
        m.setOwnerUserNo(userNo);
        grantOwner(m.getEntityNo(), userNo);
        merchantMapper.updateById(m);
        return ai.neargo.shop.support.TestLogin.consumer(mvc(), json, otpStore, phone);
    }

    /** 授予 B 端身份。**status 必须 ACTIVE**：受众解析（MerchantStaffPort）按它过滤。 */
    private void grantOwner(String merchantNo, String userNo) {
        var existing = merchantStaffMapper.selectOne(Wrappers
                .<ai.neargo.shop.merchant.entity.MchAccount>lambdaQuery()
                .eq(ai.neargo.shop.merchant.entity.MchAccount::getEntityNo, merchantNo)
                .last("limit 1"));
        if (existing != null) {
            existing.setUserNo(userNo);
            // 全量跑时前面的角色用例可能把这行种子改成了普通店员，isOwner 要置回
            existing.setIsOwner(true);
            existing.setIsPrimary(true);
            existing.setStatus(ai.neargo.shop.merchant.entity.MchAccount.ACTIVE);
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
}
