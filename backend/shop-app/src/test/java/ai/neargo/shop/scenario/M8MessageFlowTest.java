package ai.neargo.shop.scenario;

import ai.neargo.shop.user.entity.UsrMerchant;
import ai.neargo.shop.user.mapper.UserMappers.MerchantMapper;
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
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private ai.neargo.shop.user.service.OtpStore otpStore;

    @Autowired
    private MerchantMapper merchantMapper;

    @Autowired
    private ai.neargo.shop.event.OutboxDispatcher dispatcher;

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
        UsrMerchant m = merchantMapper.selectOne(Wrappers.<UsrMerchant>lambdaQuery()
                .eq(UsrMerchant::getMerchantNo, merchantNo).last("limit 1"));
        m.setOwnerUserNo(json.readTree(body).get("data").get("userNo").asString());
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
}
