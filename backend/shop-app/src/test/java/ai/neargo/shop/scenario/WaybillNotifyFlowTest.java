package ai.neargo.shop.scenario;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.Fulfillments;
import ai.neargo.shop.event.OutboxDispatcher;
import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.shop.message.NotifyScene;
import ai.neargo.shop.message.entity.MsgMessage;
import ai.neargo.shop.message.entity.MsgSubscribe;
import ai.neargo.shop.message.mapper.MessageMappers.MessageMapper;
import ai.neargo.shop.message.mapper.MessageMappers.SubscribeMapper;
import ai.neargo.shop.notify.port.StubWxSubscribeGateway;
import ai.neargo.shop.spi.logistics.LogisticsEvents;
import ai.neargo.shop.spi.notify.WxSubscribePort;
import ai.neargo.shop.support.TestLogin;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.mapper.TradeMappers.SubOrderMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 快递节点 → 买家通知（TDD-物流模块 批 4 / AC5）。
 *
 * <p>线下付款单（运单 {@code profile = SELF}）微信不会替我们推物流动态，揽收、派件、签收这三步
 * 买家只能从我们这里知道；微信支付单微信自己推，我们再发就是重复打扰。
 */
@SpringBootTest
@ActiveProfiles("test")
class WaybillNotifyFlowTest {

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private OutboxEventBus eventBus;
    @Autowired
    private OutboxDispatcher dispatcher;
    @Autowired
    private MessageMapper messageMapper;
    @Autowired
    private SubscribeMapper subscribeMapper;
    @Autowired
    private SubOrderMapper subOrderMapper;
    @Autowired
    private StubWxSubscribeGateway wxStub;

    @BeforeEach
    void drain() {
        drainOutbox();
        wxStub.clear();
    }

    @Test
    @DisplayName("★★★ 线下付款单派件 → 站内信说哪家快递、哪个单号，订阅消息走「派件」那个模板")
    void selfOrderDeliveringNotifiesBuyer() throws Exception {
        Buyer b = aBuyerWithSubOrder("wx-open-wbn-1", "SUB-WBN-1");
        grant(b.userNo, WxSubscribePort.SCENE_WAYBILL_DELIVERING);

        progressed("SUB-WBN-1", "SELF", "DELIVERING", false);

        List<MsgMessage> inbox = inboxOf(b.userNo);
        assertThat(inbox).as("线下单派件，买家一条都没收到 —— 微信不会替我们推").hasSize(1);
        assertThat(inbox.getFirst().getTitle()).isEqualTo("派件中");
        assertThat(inbox.getFirst().getBody()).contains("顺丰速运").contains("SF7001");
        assertThat(inbox.getFirst().getLink()).isEqualTo("/pages/order/index?orderNo=" + b.orderNo);
        assertThat(sentTo(b.openId)).singleElement().satisfies(s -> {
            assertThat(s.scene()).isEqualTo(WxSubscribePort.SCENE_WAYBILL_DELIVERING);
            assertThat(s.summary()).contains("SF7001").contains("派件中").contains("请保持电话畅通");
        });
    }

    @Test
    @DisplayName("★★★ 放进驿站 → 说「已到驿站」，告诉他取件码在哪")
    void lockerSaysGoPickUp() throws Exception {
        Buyer b = aBuyerWithSubOrder("wx-open-wbn-2", "SUB-WBN-2");
        grant(b.userNo, WxSubscribePort.SCENE_WAYBILL_DELIVERING);

        progressed("SUB-WBN-2", "SELF", "DELIVERING", true);

        assertThat(inboxOf(b.userNo)).singleElement().satisfies(m -> {
            assertThat(m.getTitle()).isEqualTo("已到驿站");
            assertThat(m.getBody()).contains("取件码");
        });
        assertThat(sentTo(b.openId)).singleElement()
                .satisfies(s -> assertThat(s.summary()).as("订阅消息的提示语要说去哪取件")
                        .contains("已到驿站，取件码见订单"));
    }

    @Test
    @DisplayName("★★★ 微信支付单 → 一条都不发（微信自己推，再发就是重复打扰）")
    void wxOrderIsLeftToWechat() throws Exception {
        Buyer b = aBuyerWithSubOrder("wx-open-wbn-3", "SUB-WBN-3");
        grant(b.userNo, WxSubscribePort.SCENE_WAYBILL_SIGNED);

        signed("SUB-WBN-3", "WX");
        progressed("SUB-WBN-3", "WX", "DELIVERING", false);

        assertThat(inboxOf(b.userNo)).isEmpty();
        assertThat(sentTo(b.openId)).isEmpty();
    }

    @Test
    @DisplayName("★★ 揽收 → 不发站内信（发货时说过了），只走订阅消息：线下单在微信里唯一能收到的一条")
    void pickedUpGoesOnlyToSubscribe() throws Exception {
        Buyer b = aBuyerWithSubOrder("wx-open-wbn-4", "SUB-WBN-4");
        grant(b.userNo, WxSubscribePort.SCENE_WAYBILL_PICKED_UP);

        progressed("SUB-WBN-4", "SELF", "PICKED_UP", false);

        assertThat(inboxOf(b.userNo)).isEmpty();
        assertThat(sentTo(b.openId)).singleElement()
                .satisfies(s -> assertThat(s.scene()).isEqualTo(WxSubscribePort.SCENE_WAYBILL_PICKED_UP));
    }

    @Test
    @DisplayName("★★ 签收 → 站内信「已签收」；一次授权只够一条，额度用完第二次就不再发订阅消息")
    void signedUsesItsOwnQuotaOnce() throws Exception {
        Buyer b = aBuyerWithSubOrder("wx-open-wbn-5", "SUB-WBN-5");
        grant(b.userNo, WxSubscribePort.SCENE_WAYBILL_SIGNED);

        signed("SUB-WBN-5", "SELF");
        assertThat(inboxOf(b.userNo)).singleElement().satisfies(m -> assertThat(m.getTitle()).isEqualTo("已签收"));
        assertThat(sentTo(b.openId)).hasSize(1);

        signed("SUB-WBN-5", "SELF");
        assertThat(sentTo(b.openId)).as("没额度还发 = 微信 43101 拒，且是在骚扰没授权的人").hasSize(1);
    }

    @Test
    @DisplayName("★★ 异常不发 —— 多半之后又派成了，先吓人没有用")
    void exceptionIsSilent() throws Exception {
        Buyer b = aBuyerWithSubOrder("wx-open-wbn-6", "SUB-WBN-6");

        progressed("SUB-WBN-6", "SELF", "EXCEPTION", false);

        assertThat(inboxOf(b.userNo)).isEmpty();
    }

    @Test
    @DisplayName("★★ 两个场景都登记在 NotifyScene.ALL —— 不在的话事件根本不进消费者")
    void scenesAreRegistered() {
        assertThat(NotifyScene.ALL).contains(NotifyScene.WAYBILL_PROGRESSED, NotifyScene.WAYBILL_SIGNED);
        assertThat(NotifyScene.WAYBILL_PROGRESSED).as("场景码 = 物流事件的事件码")
                .isEqualTo(new LogisticsEvents.WaybillProgressed("", "", "", "", "", "", false, 0).eventType());
        assertThat(NotifyScene.WAYBILL_SIGNED)
                .isEqualTo(new LogisticsEvents.WaybillSigned("", "", "", "", "", 0, "").eventType());
    }

    // ------------------------------------------------------------------ helpers

    private record Buyer(String userNo, String orderNo, String openId) {
    }

    private Buyer aBuyerWithSubOrder(String openId, String subOrderNo) throws Exception {
        String token = TestLogin.consumerByWechat(mvc(), json, openId);
        String body = mvc().perform(get("/mp/user/profile").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        String userNo = json.readTree(body).get("data").get("cUserNo").asString();
        String orderNo = "SO-WBN-" + System.nanoTime() % 100_000_000L;
        DataScopeContext.executeWithoutScope(() -> subOrderMapper.delete(Wrappers.<OrdSubOrder>lambdaQuery()
                .eq(OrdSubOrder::getSubOrderNo, subOrderNo)));
        OrdSubOrder sub = new OrdSubOrder();
        sub.setSubOrderNo(subOrderNo);
        sub.setOrderNo(orderNo);
        sub.setUserNo(userNo);
        sub.setEntityNo("M-WBN");
        sub.setStatus(OrdSubOrder.WAIT_FULFILL);
        sub.setFulfillment(Fulfillments.EXPRESS);
        sub.setPayAmount(1_000L);
        DataScopeContext.executeWithoutScope(() -> subOrderMapper.insert(sub));
        messageMapper.delete(Wrappers.<MsgMessage>lambdaQuery().eq(MsgMessage::getReceiverNo, userNo));
        subscribeMapper.delete(Wrappers.<MsgSubscribe>lambdaQuery().eq(MsgSubscribe::getUserNo, userNo));
        return new Buyer(userNo, orderNo, openId);
    }

    /** 给这个场景攒一份额度（桩世界的模板号与 StubWxSubscribeGateway#templateId 一致） */
    private void grant(String userNo, String wxScene) {
        MsgSubscribe s = new MsgSubscribe();
        s.setUserNo(userNo);
        s.setTemplateId("STUB_TPL_" + wxScene);
        s.setAccepted(true);
        s.setQuota(1);
        s.setAt(System.currentTimeMillis());
        subscribeMapper.insert(s);
    }

    private void progressed(String subOrderNo, String profile, String status, boolean atLocker) {
        eventBus.publish(new LogisticsEvents.WaybillProgressed("SH-" + subOrderNo, subOrderNo, profile,
                "SF", "SF7001", status, atLocker, System.currentTimeMillis()));
        drainOutbox();
    }

    private void signed(String subOrderNo, String profile) {
        eventBus.publish(new LogisticsEvents.WaybillSigned("SH-" + subOrderNo, subOrderNo, profile,
                "SF", "SF7001", System.currentTimeMillis(), "kuaidi100"));
        drainOutbox();
    }

    private void drainOutbox() {
        for (int i = 0; i < 50 && dispatcher.pendingCount() > 0; i++) {
            dispatcher.dispatchPending();
        }
    }

    private List<MsgMessage> inboxOf(String userNo) {
        return messageMapper.selectList(Wrappers.<MsgMessage>lambdaQuery()
                .eq(MsgMessage::getReceiverNo, userNo).orderByDesc(MsgMessage::getId));
    }

    private List<StubWxSubscribeGateway.Sent> sentTo(String openId) {
        return wxStub.sent().stream().filter(s -> openId.equals(s.openId())).toList();
    }

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity()).build();
    }
}
