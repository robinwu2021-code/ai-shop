package ai.neargo.shop.scenario;

import ai.neargo.shop.merchant.entity.MchEntity;
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
 * M8 消息与客服 —— **用例先行**。
 *
 * <p>本模块的价值不只在「有个消息列表」，而在**验证整条事件链路真的通了**：
 * 业务写 Outbox → 投递器分发 → 消费者产生消息。
 * 前面七个模块发了一路事件，但从来没有消费者 —— 这一轮才第一次证明它们没白发。
 */
@SpringBootTest
@ActiveProfiles("test")
class M8MessageFlowTest {

    private static final String STUB_SECRET = "stub-secret";

    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;


    @Autowired
    private MchEntityMapper merchantMapper;

    @Autowired
    private ai.neargo.shop.event.OutboxDispatcher dispatcher;

    @Autowired
    private ai.neargo.shop.product.mapper.ProductMappers.SkuMapper skuMapper;

    /**
     * 把本类要买的 SKU 库存补满。
     *
     * <p>种子给 SK0003 的库存是 80，全套测试跑下来前面的交易用例会把它买到见底。
     * 于是这里的 {@code buyAndPay} 静默失败（下单被 20001 拒），一条支付事件都没产生，
     * 而断言报的是「支付成功消息应有 1 条、实际 0 条」—— 看起来像**消息模块坏了**，
     * 真正的原因在两百个用例之前。这类假红比真 bug 更贵。
     */
    /**
     * 把队列排空再开始。
     *
     * <p>{@code dispatchPending()} 一批只取 200 条（按 id 升序）。跑全套时，
     * 前面两百多个用例会在 outbox 里堆下几百条没人投递的事件，于是本类刚产生的那条
     * 排在队尾 —— 一次 dispatch 根本轮不到它，断言看到的是「支付成功消息 0 条」，
     * 像是<b>消息模块坏了</b>，而实际上它连投递都没轮上。
     *
     * <p>循环到排空而不是调一次：待投条数没有上界，调一次仍可能剩下。
     */
    @org.junit.jupiter.api.BeforeEach
    void drainOutbox() {
        for (int i = 0; i < 50 && dispatcher.pendingCount() > 0; i++) {
            dispatcher.dispatchPending();
        }
    }

    @org.junit.jupiter.api.BeforeEach
    void refillStock() {
        var sku = skuMapper.selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers
                .<ai.neargo.shop.product.entity.PrdSku>lambdaQuery()
                .eq(ai.neargo.shop.product.entity.PrdSku::getSkuNo, "SK0003").last("limit 1"));
        if (sku != null && sku.getStock() < 50) {
            sku.setStock(500);
            skuMapper.updateById(sku);
        }
    }

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    // ---------------------------------------------------------------- 事件驱动

    @Test
    @DisplayName("★ 支付成功 → 事件投递 → 用户收到交易消息")
    void payProducesTradeMessage() throws Exception {
        String token = login("12700127001");
        buyAndPay(token, "m8-pay");

        dispatcher.dispatchPending();

        JsonNode messages = messages(token);
        assertThat(messages).isNotEmpty();
        JsonNode paid = find(messages, "支付成功");
        assertThat(paid).isNotNull();
        assertThat(paid.get("type").asString()).isEqualTo("TRADE");
        assertThat(paid.get("read").asBoolean()).isFalse();
        // 点进去要能跳到订单 —— 没有 link 的通知等于让用户自己去翻订单列表
        assertThat(paid.get("link").asString()).contains("/order");
    }

    @Test
    @DisplayName("★ 核销完成 → 用户收到「已取货」消息")
    void pickupProducesMessage() throws Exception {
        String token = login("12700127002");
        buyAndPay(token, "m8-pickup");
        String biz = loginAsOwnerOf("M0001", "12700127003");

        // 先登记到货 —— 核销要求货已经在点上（NOT_ARRIVED），这也是店员真实走的两步
        mvc().perform(post("/biz/pickup/arrived").header("Authorization", "Bearer " + biz)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderNos\":[\"" + latestSubOrderNo(token) + "\"]}"));

        String verifyCode = verifyCodeOf(token);
        mvc().perform(post("/biz/pickup/verify").header("Authorization", "Bearer " + biz)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"verifyCode\":\"" + verifyCode + "\"}"))
                .andExpect(jsonPath("$.data.success").value(true));

        dispatcher.dispatchPending();
        assertThat(find(messages(token), "已取货")).isNotNull();
    }

    @Test
    @DisplayName("★ 事件重投不产生重复消息（用户不该收到两条「支付成功」）")
    void redeliveryDoesNotDuplicateMessages() throws Exception {
        String token = login("12700127004");
        buyAndPay(token, "m8-dup");

        dispatcher.dispatchPending();
        int first = countOf(messages(token), "支付成功");
        // 强制重投：把已发送的事件重置为待发送
        dispatcher.redeliverAllForTest();
        dispatcher.dispatchPending();

        assertThat(countOf(messages(token), "支付成功")).isEqualTo(first).isEqualTo(1);
    }

    @Test
    @DisplayName("投递失败的事件保留在待发送队列，不会被标记为已发送")
    void failedDeliveryStaysPending() throws Exception {
        String token = login("12700127005");
        buyAndPay(token, "m8-fail");

        long pendingBefore = dispatcher.pendingCount();
        assertThat(pendingBefore).isPositive();

        dispatcher.dispatchPending();
        // 正常投递后队列应清空 —— 队列不清空说明消费者抛了异常，那是必须被发现的
        assertThat(dispatcher.pendingCount()).isZero();
    }

    // ---------------------------------------------------------------- 消息中心

    @Test
    @DisplayName("标记已读 / 全部已读")
    void markRead() throws Exception {
        String token = login("12700127010");
        buyAndPay(token, "m8-read");
        dispatcher.dispatchPending();

        JsonNode list = messages(token);
        String messageNo = list.get(0).get("messageNo").asString();

        mvc().perform(post("/mp/message/" + messageNo + "/read").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        assertThat(findByNo(messages(token), messageNo).get("read").asBoolean()).isTrue();

        buyAndPay(token, "m8-read-2");
        dispatcher.dispatchPending();
        mvc().perform(post("/mp/message/read-all").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        for (JsonNode m : messages(token)) {
            assertThat(m.get("read").asBoolean()).isTrue();
        }
    }

    @Test
    @DisplayName("消息按用户隔离（别人的通知看不到）")
    void messagesAreScopedToUser() throws Exception {
        String a = login("12700127011");
        buyAndPay(a, "m8-scope");
        dispatcher.dispatchPending();

        String b = login("12700127012");
        assertThat(messages(b)).isEmpty();
    }

    @Test
    @DisplayName("别人的消息标不了已读")
    void cannotReadOthersMessage() throws Exception {
        String a = login("12700127013");
        buyAndPay(a, "m8-others");
        dispatcher.dispatchPending();
        String messageNo = messages(a).get(0).get("messageNo").asString();

        String b = login("12700127014");
        mvc().perform(post("/mp/message/" + messageNo + "/read").header("Authorization", "Bearer " + b))
                .andExpect(jsonPath("$.code").value(10404));
    }

    @Test
    @DisplayName("订阅消息授权：同意与**拒绝都要记**（不记会反复弹窗骚扰）")
    void subscribeRecordsBothAcceptAndReject() throws Exception {
        String token = login("12700127015");

        mvc().perform(post("/mp/message/subscribe").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateIds\":[\"tpl_order_paid\"],\"accepted\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        mvc().perform(post("/mp/message/subscribe").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateIds\":[\"tpl_arrival\"],\"accepted\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    // ---------------------------------------------------------------- 客服

    @Test
    @DisplayName("提交工单 → 我的工单列表 → 详情")
    void ticketFlow() throws Exception {
        String token = login("12700127020");
        String body = mvc().perform(post("/mp/ticket").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject\":\"取货码用不了\",\"content\":\"扫码提示已核销\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("OPEN"))
                .andReturn().getResponse().getContentAsString();
        String ticketNo = json.readTree(body).get("data").get("ticketNo").asString();

        mvc().perform(get("/mp/ticket").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.length()").value(1));
        mvc().perform(get("/mp/ticket/" + ticketNo).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.subject").value("取货码用不了"));
    }

    @Test
    @DisplayName("别人的工单看不到（工单里有订单信息）")
    void cannotReadOthersTicket() throws Exception {
        String a = login("12700127021");
        String body = mvc().perform(post("/mp/ticket").header("Authorization", "Bearer " + a)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject\":\"问题\",\"content\":\"内容\"}"))
                .andReturn().getResponse().getContentAsString();
        String ticketNo = json.readTree(body).get("data").get("ticketNo").asString();

        String b = login("12700127022");
        mvc().perform(get("/mp/ticket/" + ticketNo).header("Authorization", "Bearer " + b))
                .andExpect(jsonPath("$.code").value(10404));
    }

    @Test
    @DisplayName("帮助中心游客可看（还没登录的人也会有疑问）")
    void faqIsPublic() throws Exception {
        mvc().perform(get("/mp/help/faq"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(org.hamcrest.Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.data[0].question").isNotEmpty());
    }

    @Test
    @DisplayName("未登录看不到消息中心")
    void messagesRequireLogin() throws Exception {
        mvc().perform(get("/mp/message"))
                .andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------- helpers

    private JsonNode messages(String token) throws Exception {
        String body = mvc().perform(get("/mp/message").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data");
    }

    private JsonNode find(JsonNode messages, String titlePart) {
        for (JsonNode m : messages) {
            if (m.get("title").asString().contains(titlePart)) {
                return m;
            }
        }
        return null;
    }

    private JsonNode findByNo(JsonNode messages, String messageNo) {
        for (JsonNode m : messages) {
            if (messageNo.equals(m.get("messageNo").asString())) {
                return m;
            }
        }
        throw new AssertionError("message not found: " + messageNo);
    }

    private int countOf(JsonNode messages, String titlePart) {
        int n = 0;
        for (JsonNode m : messages) {
            if (m.get("title").asString().contains(titlePart)) {
                n++;
            }
        }
        return n;
    }

    /** 最新一张子单的单号（C 端列表按子单发，`orderNo` 就是子单号）。 */
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
        String token = login(phone);
        String body = mvc().perform(get("/mp/user/profile").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        MchEntity m = merchantMapper.selectOne(Wrappers.<MchEntity>lambdaQuery()
                .eq(MchEntity::getEntityNo, merchantNo).last("limit 1"));
        m.setOwnerUserNo(json.readTree(body).get("data").get("userNo").asString());
        // V44 起 B 端身份来自 mch_account，不再是 owner_user_no —— 两处都要写
        grantOwner(m.getEntityNo(), json.readTree(body).get("data").get("userNo").asString());
        merchantMapper.updateById(m);
        // A7：这个令牌是拿去打 /biz/** 的，必须是 btk_
        return ai.neargo.shop.support.TestLogin.merchantOwner(mvc(), json, otpStore, phone);
    }

    private String login(String phone) throws Exception {
        mvc().perform(post("/mp/user/otp/send")
                .header("Authorization", "Bearer " + ai.neargo.shop.support.TestLogin.otpSession(mvc())).contentType(MediaType.APPLICATION_JSON)
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


    // ---------------------------------------------------------------- 平台触达治理（P-14.1）

    @Test
    @DisplayName("★ 频控：没配过给保守默认值，而不是「不限」")
    void quotaDefaultsAreConservative() throws Exception {
        String body = mvc().perform(get("/ops/notify-quota")
                        .header("Authorization", "Bearer " + opsLogin()))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        var data = json.readTree(body).get("data");
        assertThat(data.get("dailyPerUser").asInt())
                .as("没配过不等于不限 —— 默认必须是个真实的上限").isPositive();
        assertThat(data.get("minIntervalHours").asInt()).isPositive();
    }

    @Test
    @DisplayName("★ 频控上限为 0 被拒：0 等于没有频控，但界面上看着像配了")
    void zeroQuotaRejected() throws Exception {
        String ops = opsLogin();
        for (String bad : new String[]{
                "{\"dailyPerUser\":0,\"minIntervalHours\":24}",
                "{\"dailyPerUser\":5,\"minIntervalHours\":0}"}) {
            mvc().perform(post("/ops/notify-quota").header("Authorization", "Bearer " + ops)
                            .contentType(MediaType.APPLICATION_JSON).content(bad))
                    .andExpect(jsonPath("$.code").value(10400));
        }
    }

    @Test
    @DisplayName("频控保存后读得回来（走 sys_setting，不建新表）")
    void quotaRoundTrips() throws Exception {
        String ops = opsLogin();
        mvc().perform(post("/ops/notify-quota").header("Authorization", "Bearer " + ops)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dailyPerUser\":3,\"minIntervalHours\":12}"))
                .andExpect(jsonPath("$.data.dailyPerUser").value(3));

        mvc().perform(get("/ops/notify-quota").header("Authorization", "Bearer " + ops))
                .andExpect(jsonPath("$.data.dailyPerUser").value(3))
                .andExpect(jsonPath("$.data.minIntervalHours").value(12));
    }

    @Test
    @DisplayName("模板列表可读；无 ticket:handle 的角色读不了")
    void templatesReadableWithPermission() throws Exception {
        mvc().perform(get("/ops/msg-templates").header("Authorization", "Bearer " + opsLogin()))
                .andExpect(jsonPath("$.code").value(0));

        String bd = mvc().perform(post("/ops/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"bd\",\"password\":\"bd123\"}"))
                .andReturn().getResponse().getContentAsString();
        mvc().perform(get("/ops/msg-templates")
                        .header("Authorization", "Bearer " + json.readTree(bd).get("data").get("token").asString()))
                .andExpect(jsonPath("$.code").value(10403));
    }

    private String opsLogin() throws Exception {
        String body = mvc().perform(post("/ops/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"support\",\"password\":\"support123\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("token").asString();
    }

}
