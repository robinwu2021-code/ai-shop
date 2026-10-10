package ai.neargo.shop.scenario;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.OtpStore;
import ai.neargo.shop.event.OutboxDispatcher;
import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.shop.merchant.entity.MchAccount;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchAccountMapper;
import ai.neargo.shop.message.entity.MsgMessage;
import ai.neargo.shop.message.entity.MsgPushToken;
import ai.neargo.shop.message.entity.MsgSubscribe;
import ai.neargo.shop.message.mapper.MessageMappers.PushTokenMapper;
import ai.neargo.shop.message.mapper.MessageMappers.SubscribeMapper;
import ai.neargo.shop.notify.port.StubPushGateway;
import ai.neargo.shop.notify.port.StubWxSubscribeGateway;
import ai.neargo.shop.spi.marketing.MarketingEvents;
import ai.neargo.shop.spi.product.ProductEvents;
import ai.neargo.shop.spi.notify.WxSubscribePort;
import ai.neargo.shop.spi.trade.OrderEvents;
import ai.neargo.shop.support.TestLogin;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 能走微信的都走微信（TDD-微信订阅消息优先）。
 *
 * <p>买家四条：售后驳回带理由、退货待寄回、拼团结果、商家配送「开始配送」（快递发货不发 —— 那是重复）。
 * 商家三条：新订单 App 与微信都发；售后申请、新评价微信发出去了就不再走 App，没发出去才回落。
 *
 * <p>桩世界的模板号是 {@code STUB_TPL_<场景>}（{@link StubWxSubscribeGateway#templateId}），
 * 额度按它记 —— 与生产上「前端上报的号 = 后端发送用的号」是同一条闭环。
 */
@SpringBootTest
@ActiveProfiles("test")
class WxSubscribeMoreFlowTest {

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private OtpStore otpStore;
    @Autowired
    private OutboxEventBus eventBus;
    @Autowired
    private OutboxDispatcher dispatcher;
    @Autowired
    private SubscribeMapper subscribeMapper;
    @Autowired
    private PushTokenMapper tokenMapper;
    @Autowired
    private MchAccountMapper accountMapper;
    @Autowired
    private StubWxSubscribeGateway wxStub;
    @Autowired
    private StubPushGateway pushStub;

    @BeforeEach
    void drain() {
        drainOutbox();
        wxStub.clear();
        pushStub.clear();
    }

    // ---------------------------------------------------------------- 买家

    @Test
    @DisplayName("★★★ 售后被驳回 → 微信告诉买家，**带上商家的理由**（AC2）")
    void rejectedCarriesReason() throws Exception {
        Person b = aWechatUser("wx-open-wsm-1");
        grant(b.userNo, WxSubscribePort.SCENE_AFTER_SALE_RESULT);

        eventBus.publish(new OrderEvents.AfterSaleDecided("AS-WSM-1", "SUB-WSM-1", b.userNo,
                OrderEvents.AfterSaleDecided.REJECTED, "商品已拆封影响二次销售"));
        drainOutbox();

        assertThat(sentTo(b.openId)).singleElement().satisfies(s -> {
            assertThat(s.scene()).isEqualTo(WxSubscribePort.SCENE_AFTER_SALE_RESULT);
            assertThat(s.summary()).contains("SUB-WSM-1").contains("未通过").contains("商品已拆封影响二次销售");
        });
    }

    @Test
    @DisplayName("★★ 退货待寄回 → 微信提醒，说清有时限（AC3）")
    void returnWaitSaysDeadline() throws Exception {
        Person b = aWechatUser("wx-open-wsm-2");
        grant(b.userNo, WxSubscribePort.SCENE_RETURN_WAIT);

        eventBus.publish(new OrderEvents.AfterSaleDecided("AS-WSM-2", "SUB-WSM-2", b.userNo,
                OrderEvents.AfterSaleDecided.RETURN_WAIT, null));
        drainOutbox();

        assertThat(sentTo(b.openId)).singleElement().satisfies(s -> {
            assertThat(s.scene()).isEqualTo(WxSubscribePort.SCENE_RETURN_WAIT);
            assertThat(s.summary()).contains("AS-WSM-2").contains("待寄回").contains("超时");
        });
    }

    @Test
    @DisplayName("★★★ 拼团没成 → 授权过的团员收到微信，**说退款**；没授权的静默跳过、不报错（AC4）")
    void groupFailedTellsRefund() throws Exception {
        Person a = aWechatUser("wx-open-wsm-3a");
        Person c = aWechatUser("wx-open-wsm-3b");
        grant(a.userNo, WxSubscribePort.SCENE_GROUP_RESULT);

        eventBus.publish(new MarketingEvents.GroupSettled("GRP-WSM-3", MarketingEvents.GroupSettled.FAILED,
                "砂糖橘 5 斤", List.of(a.userNo, c.userNo)));
        drainOutbox();

        assertThat(sentTo(a.openId)).singleElement().satisfies(s -> {
            assertThat(s.scene()).isEqualTo(WxSubscribePort.SCENE_GROUP_RESULT);
            assertThat(s.summary()).contains("砂糖橘").contains("失败").contains("退");
        });
        assertThat(sentTo(c.openId)).as("没授权就不发 —— 微信 43101，而且是在打扰没同意的人").isEmpty();
    }

    @Test
    @DisplayName("★★★ 商家配送「开始配送」发微信；**快递发货不发**（微信支付单微信推、线下单物流揽收推）（AC5 / AC6）")
    void deliveryStartOnlyForMerchantDelivery() throws Exception {
        Person b = aWechatUser("wx-open-wsm-4");
        grant(b.userNo, WxSubscribePort.SCENE_DELIVERY_START);

        eventBus.publish(new OrderEvents.SubOrderShipped("SUB-WSM-4E", "SO-WSM-4E", "M-WSM", b.userNo,
                "EXPRESS", "SF", "SF4001"));
        drainOutbox();
        assertThat(sentTo(b.openId)).as("快递发货再发一条微信就是重复").isEmpty();

        eventBus.publish(new OrderEvents.SubOrderShipped("SUB-WSM-4D", "SO-WSM-4D", "M-WSM", b.userNo,
                "MERCHANT_DELIVERY", null, null));
        drainOutbox();
        assertThat(sentTo(b.openId)).singleElement().satisfies(s -> {
            assertThat(s.scene()).isEqualTo(WxSubscribePort.SCENE_DELIVERY_START);
            assertThat(s.summary()).contains("SUB-WSM-4D").contains("配送中");
        });
    }

    // ---------------------------------------------------------------- 商家

    @Test
    @DisplayName("★★★ 新订单：**App 响铃与微信都发** —— 厂商通道没报备，App 在后台收不到（AC10）")
    void newOrderGoesBothWays() throws Exception {
        Owner o = anOwnerWithDevice("wx-open-wsm-5", "M-WSM-5", "cid-wsm-5");
        grant(o.userNo, WxSubscribePort.SCENE_MCH_NEW_ORDER);

        eventBus.publish(new OrderEvents.SubOrderPaid("SUB-WSM-5", "SO-WSM-5", o.entityNo, "ST-WSM-5",
                "U-BUYER-WSM-5", 12_80L));
        drainOutbox();

        assertThat(sentTo(o.openId)).singleElement().satisfies(s -> {
            assertThat(s.scene()).isEqualTo(WxSubscribePort.SCENE_MCH_NEW_ORDER);
            assertThat(s.summary()).contains("SUB-WSM-5").contains("12.80元");
        });
        assertThat(pushTo("cid-wsm-5")).singleElement()
                .satisfies(p -> assertThat(p.level()).isEqualTo("RING"));
    }

    @Test
    @DisplayName("★★★ 售后申请：微信发出去了 → **不再走 App**；跳转页落在小程序的商家分包（AC10）")
    void afterSaleWxFirstSkipsApp() throws Exception {
        Owner o = anOwnerWithDevice("wx-open-wsm-6", "M-WSM-6", "cid-wsm-6");
        grant(o.userNo, WxSubscribePort.SCENE_MCH_AFTER_SALE);

        eventBus.publish(new OrderEvents.AfterSaleApplied("AS-WSM-6", "SUB-WSM-6", o.entityNo,
                "ST-WSM-6", "U-BUYER-WSM-6", "REFUND_ONLY", 500L));
        drainOutbox();

        assertThat(sentTo(o.openId)).singleElement().satisfies(s -> {
            assertThat(s.scene()).isEqualTo(WxSubscribePort.SCENE_MCH_AFTER_SALE);
            assertThat(s.summary()).contains("SUB-WSM-6").contains("pkg-biz/pages/after-sale/index");
        });
        assertThat(pushTo("cid-wsm-6")).as("微信到了还响 App = 同一件事说两遍").isEmpty();
    }

    @Test
    @DisplayName("★★★ 新评价：微信没额度 → **回落 App**，商家照样知道（AC10）")
    void reviewFallsBackToAppWithoutQuota() throws Exception {
        Owner o = anOwnerWithDevice("wx-open-wsm-7", "M-WSM-7", "cid-wsm-7");

        eventBus.publish(new ProductEvents.ReviewCreated("RV-WSM-7", o.entityNo, "ST-WSM-7",
                "G-WSM-7", 1));
        drainOutbox();

        assertThat(sentTo(o.openId)).isEmpty();
        assertThat(pushTo("cid-wsm-7")).as("微信没发出去，App 必须补上 —— 否则这条差评谁都不知道")
                .hasSize(1);
    }

    @Test
    @DisplayName("★★ 商家在小程序里上报授权 → 额度记在他 C 端的 user_no 上（发送时按它查 openid）（AC8）")
    void bizSubscribeLandsOnSameUserNo() throws Exception {
        String phone = "12600139001";
        String btk = TestLogin.merchantOwner(mvc(), json, otpStore, phone);
        String ctk = TestLogin.consumer(mvc(), json, otpStore, phone);
        String userNo = json.readTree(mvc().perform(get("/mp/user/profile").header("Authorization", "Bearer " + ctk))
                .andReturn().getResponse().getContentAsString()).get("data").get("userNo").asString();
        subscribeMapper.delete(Wrappers.<MsgSubscribe>lambdaQuery().eq(MsgSubscribe::getUserNo, userNo));

        mvc().perform(post("/biz/message/subscribe").header("Authorization", "Bearer " + btk)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateIds\":[\"STUB_TPL_MCH_NEW_ORDER\"],\"accepted\":true}"))
                .andExpect(status().isOk());

        assertThat(subscribeMapper.selectList(Wrappers.<MsgSubscribe>lambdaQuery()
                .eq(MsgSubscribe::getUserNo, userNo))).singleElement().satisfies(s -> {
                    assertThat(s.getTemplateId()).isEqualTo("STUB_TPL_MCH_NEW_ORDER");
                    assertThat(s.getQuota()).isEqualTo(1);
                });
    }

    // ---------------------------------------------------------------- helpers

    private record Person(String userNo, String openId) {
    }

    private record Owner(String userNo, String openId, String entityNo) {
    }

    private Person aWechatUser(String openId) throws Exception {
        String token = TestLogin.consumerByWechat(mvc(), json, openId);
        String body = mvc().perform(get("/mp/user/profile").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        String userNo = json.readTree(body).get("data").get("cUserNo").asString();
        subscribeMapper.delete(Wrappers.<MsgSubscribe>lambdaQuery().eq(MsgSubscribe::getUserNo, userNo));
        return new Person(userNo, openId);
    }

    /** 小程序里登录过的人开了店：商家账号与 C 端同一个 user_no，App 也绑了一台设备 */
    private Owner anOwnerWithDevice(String openId, String entityNo, String clientId) throws Exception {
        Person p = aWechatUser(openId);
        DataScopeContext.executeWithoutScope(() -> accountMapper.delete(Wrappers.<MchAccount>lambdaQuery()
                .eq(MchAccount::getEntityNo, entityNo)));
        MchAccount a = new MchAccount();
        a.setMchAccountNo("MA-" + entityNo);
        a.setEntityNo(entityNo);
        a.setUserNo(p.userNo);
        a.setIsOwner(true);
        a.setIsPrimary(true);
        a.setStatus(MchAccount.ACTIVE);
        DataScopeContext.executeWithoutScope(() -> accountMapper.insert(a));
        tokenMapper.delete(Wrappers.<MsgPushToken>lambdaQuery().eq(MsgPushToken::getClientId, clientId));
        MsgPushToken t = new MsgPushToken();
        t.setReceiverType(MsgMessage.RECEIVER_STAFF);
        t.setReceiverNo(p.userNo);
        t.setPlatform(MsgPushToken.APP_ANDROID);
        t.setProvider("GETUI");
        t.setClientId(clientId);
        tokenMapper.insert(t);
        return new Owner(p.userNo, openId, entityNo);
    }

    private void grant(String userNo, String wxScene) {
        MsgSubscribe s = new MsgSubscribe();
        s.setUserNo(userNo);
        s.setTemplateId("STUB_TPL_" + wxScene);
        s.setAccepted(true);
        s.setQuota(1);
        s.setAt(System.currentTimeMillis());
        subscribeMapper.insert(s);
    }

    private void drainOutbox() {
        for (int i = 0; i < 50 && dispatcher.pendingCount() > 0; i++) {
            dispatcher.dispatchPending();
        }
    }

    private List<StubWxSubscribeGateway.Sent> sentTo(String openId) {
        return wxStub.sent().stream().filter(s -> openId.equals(s.openId())).toList();
    }

    private List<StubPushGateway.Sent> pushTo(String clientId) {
        return pushStub.sent().stream().filter(s -> clientId.equals(s.clientId())).toList();
    }

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity()).build();
    }
}
