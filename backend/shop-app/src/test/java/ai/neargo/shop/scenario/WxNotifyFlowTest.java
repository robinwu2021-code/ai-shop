package ai.neargo.shop.scenario;

import ai.neargo.shop.notify.port.StubWxSubscribeGateway;
import ai.neargo.shop.merchant.entity.MchEntity;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchEntityMapper;
import ai.neargo.shop.message.entity.MsgSubscribe;
import ai.neargo.shop.message.entity.SysNotifyLog;
import ai.neargo.shop.message.mapper.MessageMappers.NotifyLogMapper;
import ai.neargo.shop.message.mapper.MessageMappers.SubscribeMapper;
import ai.neargo.shop.spi.trade.OrderEvents;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 微信订阅消息全链路（TDD-通知与消息推送 · 一期）。
 *
 * <p><b>走真实链路而不是直接调 sender</b>：登录（微信桩建出带 openid 的身份）→
 * 授权上报（额度 +1）→ 下单支付 → 自提点标到货（发 ORDER_ARRIVED 事件）→
 * Outbox 投递 → 站内信落库 + 订阅消息进桩。任何一环断了，这里都该红。
 */
@SpringBootTest
@ActiveProfiles("test")
class WxNotifyFlowTest {

    private static final String STUB_SECRET = "stub-secret";

    /** 桩世界的模板号（与 {@code StubWxSubscribeGateway#templateId} 一致）。 */
    private static final String TPL_ARRIVED = "STUB_TPL_ORDER_ARRIVED";
    private static final String TPL_REFUNDED = "STUB_TPL_REFUNDED";

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;
    @Autowired
    private ai.neargo.shop.event.OutboxDispatcher dispatcher;
    @Autowired
    private ai.neargo.shop.event.OutboxEventBus eventBus;
    @Autowired
    private StubWxSubscribeGateway wxStub;
    /** 走 @Primary 的留痕装饰器，与领域拿到的是同一个 —— 直接注桩就绕过了留痕那一段 */
    @Autowired
    private ai.neargo.shop.spi.notify.WxSubscribePort wxPort;
    @Autowired
    private SubscribeMapper subscribeMapper;
    @Autowired
    private NotifyLogMapper notifyLogMapper;
    @Autowired
    private MchEntityMapper merchantMapper;
    @Autowired
    private ai.neargo.shop.merchant.mapper.MerchantMappers.MchAccountMapper merchantStaffMapper;
    @Autowired
    private ai.neargo.shop.product.mapper.ProductMappers.SkuMapper skuMapper;

    @BeforeEach
    void drainOutbox() {
        // 理由同 M8MessageFlowTest：前面用例堆下的事件会把本类的挤出一批 200 条的窗口
        for (int i = 0; i < 50 && dispatcher.pendingCount() > 0; i++) {
            dispatcher.dispatchPending();
        }
        wxStub.clear();
    }

    @Autowired
    private ai.neargo.shop.community.mapper.CommunityMappers.PickupPointMapper pickupMapper;
    @Autowired
    private ai.neargo.shop.merchant.mapper.MerchantMappers.MchStoreMapper storeMapper;

    /**
     * 自提点与门店的种子复位。理由同 {@code refillStock}：全量跑时，
     * 前面的治理/入驻用例会把 PP0001 下架或改属 —— 于是 markArrived 报 10403，
     * 断言看到的是「消息模块坏了」，而真正的原因在两百个用例之前。
     */
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

    // ---------------------------------------------------------------- 全链路

    @Test
    @DisplayName("★ 授权 → 到货 → 站内信 + 订阅消息进桩 + 额度耗尽 + 留痕")
    void arrivedSendsSubscribeMessage() throws Exception {
        String openId = "wx-open-arrive-1";
        String token = ai.neargo.shop.support.TestLogin.consumerByWechat(mvc(), json, openId);
        String userNo = profileUserNo(token);

        subscribe(token, TPL_ARRIVED, true);
        buyAndPay(token, "wxn-arrive");
        dispatcher.dispatchPending();   // 先消化 ORDER_PAID，别和到货事件混在一批里断言

        String biz = loginAsOwnerOf("M0001", "12700127101");
        markArrived(biz, subOrderNoOf(token));
        dispatcher.dispatchPending();

        // 站内信必达
        assertThat(find(messages(token), "到货了")).isNotNull();

        // 订阅消息进桩，发给登录时登记的那个 openid
        List<StubWxSubscribeGateway.Sent> sent = wxStub.sent().stream()
                .filter(s -> openId.equals(s.openId())).toList();
        assertThat(sent).hasSize(1);
        assertThat(sent.getFirst().scene()).isEqualTo("ORDER_ARRIVED");

        // 一次授权只能发一次：额度归零
        MsgSubscribe sub = subscribeMapper.selectOne(Wrappers.<MsgSubscribe>lambdaQuery()
                .eq(MsgSubscribe::getUserNo, userNo)
                .eq(MsgSubscribe::getTemplateId, TPL_ARRIVED).last("limit 1"));
        assertThat(sub.getQuota()).isZero();

        // 发送留痕（WXSUB 通道）
        assertThat(notifyLogMapper.selectCount(Wrappers.<SysNotifyLog>lambdaQuery()
                .eq(SysNotifyLog::getChannel, SysNotifyLog.WXSUB)
                .eq(SysNotifyLog::getStatus, SysNotifyLog.SENT))).isPositive();
    }

    @Test
    @DisplayName("★ 额度用尽后再到货：站内信照发，订阅消息不再发（一次授权≠无限次打扰）")
    void quotaExhaustedFallsBackToInApp() throws Exception {
        String openId = "wx-open-quota-1";
        String token = ai.neargo.shop.support.TestLogin.consumerByWechat(mvc(), json, openId);

        subscribe(token, TPL_ARRIVED, true);
        String biz = loginAsOwnerOf("M0001", "12700127102");

        buyAndPay(token, "wxn-quota-a");
        dispatcher.dispatchPending();
        markArrived(biz, subOrderNoOf(token));
        dispatcher.dispatchPending();

        buyAndPay(token, "wxn-quota-b");
        dispatcher.dispatchPending();
        markArrived(biz, subOrderNoOf(token));
        dispatcher.dispatchPending();

        // 两批到货 → 两条站内信；订阅消息只有额度内的那一条
        assertThat(countOf(messages(token), "到货了")).isEqualTo(2);
        assertThat(wxStub.sent().stream().filter(s -> openId.equals(s.openId()))).hasSize(1);
    }

    @Test
    @DisplayName("未授权只有站内信 —— 订阅消息静默跳过，不报错不留 FAILED")
    void noAuthorizationNoSubscribeMessage() throws Exception {
        String openId = "wx-open-noauth-1";
        String token = ai.neargo.shop.support.TestLogin.consumerByWechat(mvc(), json, openId);

        buyAndPay(token, "wxn-noauth");
        dispatcher.dispatchPending();
        String biz = loginAsOwnerOf("M0001", "12700127103");
        markArrived(biz, subOrderNoOf(token));
        dispatcher.dispatchPending();

        assertThat(find(messages(token), "到货了")).isNotNull();
        assertThat(wxStub.sent().stream().filter(s -> openId.equals(s.openId()))).isEmpty();
    }

    @Test
    @DisplayName("拒绝授权也记了，但不产生额度 —— 不发")
    void rejectedAuthorizationDoesNotSend() throws Exception {
        String openId = "wx-open-reject-1";
        String token = ai.neargo.shop.support.TestLogin.consumerByWechat(mvc(), json, openId);
        String userNo = profileUserNo(token);

        subscribe(token, TPL_ARRIVED, false);

        MsgSubscribe sub = subscribeMapper.selectOne(Wrappers.<MsgSubscribe>lambdaQuery()
                .eq(MsgSubscribe::getUserNo, userNo)
                .eq(MsgSubscribe::getTemplateId, TPL_ARRIVED).last("limit 1"));
        assertThat(sub).isNotNull();          // 拒绝也要记（防反复弹窗）
        assertThat(sub.getQuota()).isZero();  // 但不产生发送额度

        buyAndPay(token, "wxn-reject");
        dispatcher.dispatchPending();
        String biz = loginAsOwnerOf("M0001", "12700127104");
        markArrived(biz, subOrderNoOf(token));
        dispatcher.dispatchPending();

        assertThat(wxStub.sent().stream().filter(s -> openId.equals(s.openId()))).isEmpty();
    }

    @Test
    @DisplayName("退款完成 → 订阅消息（金额已格式化）")
    void refundSendsSubscribeMessage() throws Exception {
        String openId = "wx-open-refund-1";
        String token = ai.neargo.shop.support.TestLogin.consumerByWechat(mvc(), json, openId);
        String userNo = profileUserNo(token);

        subscribe(token, TPL_REFUNDED, true);

        // 售后全流程另有 M6 覆盖；这里从事件切入，验证「事件 → 订阅消息」这一段
        eventBus.publish(new OrderEvents.AfterSaleRefunded("AS-WXN-1", "SO-WXN-1", userNo, 1250L));
        dispatcher.dispatchPending();

        assertThat(find(messages(token), "退款已处理")).isNotNull();
        List<StubWxSubscribeGateway.Sent> sent = wxStub.sent().stream()
                .filter(s -> openId.equals(s.openId())).toList();
        assertThat(sent).hasSize(1);
        assertThat(sent.getFirst().scene()).isEqualTo("REFUNDED");
        assertThat(sent.getFirst().summary()).contains("12.50元");
    }

    @Test
    @DisplayName("★ 退款的提示语也能自定义 —— 两条微信模板必须对称，一个能改一个不能最难查")
    void refundTipIsCustomisableToo() {
        String openId = "wx-open-tip-1";

        /*
         * 到货那条早就放开了 tip，退款那条一直写死在网关里。
         * 不对称的后果不是「少一个功能」，而是运营在页面上看到两条长得一样的模板，
         * 改其中一条没反应 —— 他不会想到「这条没放开」，只会以为保存失败了。
         */
        wxPort.sendRefunded(openId, "9.90元", "pages/orders/index", "已退回原支付账户");

        var sent = wxStub.sent().stream().filter(s -> openId.equals(s.openId())).toList();
        assertThat(sent).hasSize(1);
        assertThat(sent.getFirst().summary())
                .as("自定义话术要真的传到通道，桩摘要里看得见")
                .contains("已退回原支付账户");

        // 不传时回落默认话术，不是发一条空的
        wxPort.sendRefunded("wx-open-tip-2", "1.00元", "pages/orders/index", null);
        var fallback = wxStub.sent().stream()
                .filter(s -> "wx-open-tip-2".equals(s.openId())).toList();
        assertThat(fallback).hasSize(1);
        assertThat(fallback.getFirst().summary()).doesNotContain("null");
    }

    @Test
    @DisplayName("同一买家一批到货多单 → 事件按人聚合，站内信与订阅消息各一条")
    void batchArrivalIsAggregatedPerUser() throws Exception {
        String openId = "wx-open-batch-1";
        String token = ai.neargo.shop.support.TestLogin.consumerByWechat(mvc(), json, openId);

        subscribe(token, TPL_ARRIVED, true);
        buyAndPay(token, "wxn-batch-a");
        buyAndPay(token, "wxn-batch-b");
        dispatcher.dispatchPending();

        // 两单一起标到货（一车货一次登记）
        JsonNode records = orders(token);
        String biz = loginAsOwnerOf("M0001", "12700127105");
        markArrived(biz, records.get(0).get("orderNo").asString(),
                records.get(1).get("orderNo").asString());
        dispatcher.dispatchPending();

        assertThat(countOf(messages(token), "到货了")).isEqualTo(1);
        assertThat(find(messages(token), "到货了").get("body").asString()).contains("2 件");
        assertThat(wxStub.sent().stream().filter(s -> openId.equals(s.openId()))).hasSize(1);
    }

    // ---------------------------------------------------------------- helpers

    private void subscribe(String token, String templateId, boolean accepted) throws Exception {
        mvc().perform(post("/mp/message/subscribe").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"templateIds\":[\"" + templateId + "\"],\"accepted\":" + accepted + "}"));
    }

    /** 标到货并**断言真的推进了**：静默失败会让后面的断言报成「消息模块坏了」。 */
    private void markArrived(String bizToken, String... subOrderNos) throws Exception {
        StringBuilder arr = new StringBuilder();
        for (String no : subOrderNos) {
            if (!arr.isEmpty()) {
                arr.append(',');
            }
            arr.append('"').append(no).append('"');
        }
        String body = mvc().perform(post("/biz/pickup/arrived")
                        .header("Authorization", "Bearer " + bizToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pickupNo\":\"PP0001\",\"orderNos\":[" + arr + "]}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(body).get("data").size())
                .as("markArrived 没有推进任何子单（状态不是 WAIT_FULFILL？作用域被谁改了？）：%s", body)
                .isEqualTo(subOrderNos.length);
    }

    private String profileUserNo(String token) throws Exception {
        String body = mvc().perform(get("/mp/user/profile").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("userNo").asString();
    }

    private JsonNode orders(String token) throws Exception {
        String body = mvc().perform(get("/mp/order").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("records");
    }

    /** 最新一单的子单号（/mp/order 的 {@code orderNo} 字段下发的就是子单号，见 orderView）。 */
    private String subOrderNoOf(String token) throws Exception {
        return orders(token).get(0).get("orderNo").asString();
    }

    private JsonNode messages(String token) throws Exception {
        String body = mvc().perform(get("/mp/message").header("Authorization", "Bearer " + token))
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

    private int countOf(JsonNode messages, String titlePart) {
        int n = 0;
        for (JsonNode m : messages) {
            if (m.get("title").asString().contains(titlePart)) {
                n++;
            }
        }
        return n;
    }

    /** 下单并支付，每一步都断言 —— 静默失败的表现是两百行之外的「消息为空」。 */
    private void buyAndPay(String token, String idemKey) throws Exception {
        mvc().perform(post("/mp/cart/add").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goodsNo\":\"G0002\",\"skuNo\":\"SK0003\",\"qty\":1}"))
                .andExpect(status().isOk());
        String body = mvc().perform(post("/mp/order").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", idemKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fulfillment\":\"STORE_PICKUP\",\"pickupNo\":\"PP0001\"}"))
                .andReturn().getResponse().getContentAsString();
        var payNode = json.readTree(body).get("data");
        assertThat(payNode == null ? null : payNode.get("payOrderNo"))
                .as("下单失败：%s", body).isNotNull();
        String payOrderNo = payNode.get("payOrderNo").asString();
        mvc().perform(post("/callback/pay/stub").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outTradeNo\":\"" + payOrderNo + "\",\"transactionId\":\"TX-" + idemKey
                                + "\",\"sign\":\"" + STUB_SECRET + "\"}"))
                .andExpect(status().isOk());
    }

    private String loginAsOwnerOf(String merchantNo, String phone) throws Exception {
        String token = ai.neargo.shop.support.TestLogin.consumer(mvc(), json, otpStore, phone);
        String userNo = profileUserNo(token);
        MchEntity m = merchantMapper.selectOne(Wrappers.<MchEntity>lambdaQuery()
                .eq(MchEntity::getEntityNo, merchantNo).last("limit 1"));
        m.setOwnerUserNo(userNo);
        grantOwner(m.getEntityNo(), userNo);
        merchantMapper.updateById(m);
        // A7：这个令牌是拿去打 /biz/** 的，必须是 btk_
        return ai.neargo.shop.support.TestLogin.merchantOwner(mvc(), json, otpStore, phone);
    }

    /** 授予 B 端身份：写一条 owner 成员行（幂等）。与 M8MessageFlowTest 同款。 */
    private void grantOwner(String merchantNo, String userNo) {
        var existing = merchantStaffMapper.selectOne(Wrappers
                .<ai.neargo.shop.merchant.entity.MchAccount>lambdaQuery()
                .eq(ai.neargo.shop.merchant.entity.MchAccount::getEntityNo, merchantNo)
                .last("limit 1"));
        if (existing != null) {
            existing.setUserNo(userNo);
            // 全量跑时前面的角色用例可能把这行种子改成了普通店员 ——
            // 只改 userNo 会继承那个身份，/biz/pickup/* 直接 10403
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
