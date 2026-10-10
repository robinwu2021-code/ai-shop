package ai.neargo.shop.scenario;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.event.OutboxDispatcher;
import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.shop.merchant.entity.MchAccount;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchAccountMapper;
import ai.neargo.shop.message.entity.MsgMessage;
import ai.neargo.shop.message.entity.MsgPushToken;
import ai.neargo.shop.message.entity.NotifyChannel;
import ai.neargo.shop.message.mapper.MessageMappers.MessageMapper;
import ai.neargo.shop.message.mapper.MessageMappers.PushTokenMapper;
import ai.neargo.shop.message.notify.MerchantChannelService;
import ai.neargo.shop.message.notify.StubWeComBotSender;
import ai.neargo.shop.notify.port.StubPushGateway;
import ai.neargo.shop.spi.trade.OrderEvents;
import ai.neargo.shop.support.TestLogin;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 来单 → 商家自己的企微群，**整条链路**（TDD-商家企微群来单通知 AC1 · AC2）。
 *
 * <p>真的那几段都是真的：事件经 outbox 分发、商家那一行从 {@code notify_channel} 里读、
 * URL 从 {@code secret_cipher} 解密出来。只有最后一步（往企微发 HTTP）是替身 ——
 * 否则测试要么真往群里发消息，要么等 5 秒超时。
 *
 * <p>{@code shop.notify.cred-key} 配在 {@code application-test.yml} 里（**不是**这个类的
 * {@code properties}）：没它 {@code upsert} 会拒（「绝不明文落库」），而用 {@code properties}
 * 会另起一个上下文、重跑 {@code schema-test.sql} 并撞唯一键 —— 单独绿、全量红。
 */
@SpringBootTest
@ActiveProfiles("test")
class WeComOrderAlertFlowTest {

    private static final String GROUP = "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=flow-test";

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private OutboxEventBus eventBus;
    @Autowired
    private OutboxDispatcher dispatcher;
    @Autowired
    private MerchantChannelService channels;
    @Autowired
    private MchAccountMapper accountMapper;
    @Autowired
    private PushTokenMapper tokenMapper;
    @Autowired
    private MessageMapper messageMapper;
    @Autowired
    private StubPushGateway pushStub;

    /** 唯一的替身：真发 HTTP 那一步（桩 bean，不是 @MockitoBean —— 见类注释） */
    @Autowired
    private StubWeComBotSender botSender;

    @BeforeEach
    void drain() {
        drainOutbox();
        pushStub.clear();
        botSender.clear();
    }

    @Test
    @DisplayName("★★★ 付款成功 → 发到**那个商家配的**群，内容带单号与金额（AC1 · AC2）")
    void paidOrderAlertsWeCom() throws Exception {
        String entityNo = "M-WCA-1";
        anOwnerWithDevice("wx-open-wca-1", entityNo, "cid-wca-1");
        configureGroup("ST-" + entityNo, GROUP);

        eventBus.publish(new OrderEvents.SubOrderPaid("SUB-WCA-1", "SO-WCA-1", entityNo,
                "ST-" + entityNo, "U-BUYER-WCA-1", 12_80L));
        drainOutbox();

        assertThat(botSender.sent()).singleElement().satisfies(s -> {
            assertThat(s.webhook()).isEqualTo(GROUP);
            assertThat(s.content()).contains("**新订单**").contains("SUB-WCA-1").contains("￥12.80");
        });
    }

    @Test
    @DisplayName("★★★ 商家没配群 → 一条都不发，**绝不回落到平台那条 env**（AC2）")
    void unconfiguredMerchantSendsNothing() throws Exception {
        String entityNo = "M-WCA-2";
        anOwnerWithDevice("wx-open-wca-2", entityNo, "cid-wca-2");

        eventBus.publish(new OrderEvents.SubOrderPaid("SUB-WCA-2", "SO-WCA-2", entityNo,
                "ST-" + entityNo, "U-BUYER-WCA-2", 500L));
        drainOutbox();

        assertThat(botSender.sent()).isEmpty();
    }

    @Test
    @DisplayName("★★★ 群发失败**不拖累**站内信与 App 推送 —— 四条出口各自独立（AC1）")
    void weComFailureDoesNotBlockOtherChannels() throws Exception {
        String entityNo = "M-WCA-3";
        Owner o = anOwnerWithDevice("wx-open-wca-3", entityNo, "cid-wca-3");
        configureGroup("ST-" + entityNo, GROUP);
        botSender.failNext(); // 企微那条炸了（限流、URL 失效、网络不通都长这样）

        eventBus.publish(new OrderEvents.SubOrderPaid("SUB-WCA-3", "SO-WCA-3", entityNo,
                "ST-" + entityNo, "U-BUYER-WCA-3", 900L));
        drainOutbox();

        /*
         * **这一条才是真正的量具**：站内信与推送在 weComOrderAlert 之前就发完了，
         * 所以光断言它们在，拆掉 WeComOrderAlert 的 try/catch 也不会红 ——
         * 异常冒到 outbox 消费者那里，事件会被判失败并留在待投里重投，
         * 于是站内信迟早被发第二遍。pendingCount 才看得见这件事。
         */
        assertThat(dispatcher.pendingCount()).as("群发炸了不该让这条事件重投").isZero();
        assertThat(inbox(o.userNo())).as("站内信该照样进来").isNotEmpty();
        assertThat(pushStub.sent().stream()
                .anyMatch(p -> "cid-wca-3".equals(p.clientId())))
                .as("App 推送该照样响").isTrue();
    }

    // ------------------------------------------------------------------ 辅助

    /** owner_no 存的是**门店号**（2026-10-10 订正：群按门店不按主体） */
    private void configureGroup(String storeNo, String url) {
        channels.upsert(storeNo, NotifyChannel.TYPE_WEBHOOK, NotifyChannel.PROV_WECOM,
                "{}", "{\"webhook\":\"" + url + "\"}", "test");
    }

    private java.util.List<MsgMessage> inbox(String userNo) {
        return DataScopeContext.executeWithoutScope(() ->
                messageMapper.selectList(Wrappers.<MsgMessage>lambdaQuery()
                        .eq(MsgMessage::getReceiverNo, userNo)));
    }

    private record Owner(String userNo, String entityNo) {
    }

    /** 小程序里登录过的人开了店，App 也绑了一台设备（与 WxSubscribeMoreFlowTest 同一个形状） */
    private Owner anOwnerWithDevice(String openId, String entityNo, String clientId) throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity()).build();
        String token = TestLogin.consumerByWechat(mvc, json, openId);
        String body = mvc.perform(get("/mp/user/profile").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        String userNo = json.readTree(body).get("data").get("cUserNo").asString();

        DataScopeContext.executeWithoutScope(() -> accountMapper.delete(
                Wrappers.<MchAccount>lambdaQuery().eq(MchAccount::getEntityNo, entityNo)));
        MchAccount a = new MchAccount();
        a.setMchAccountNo("MA-" + entityNo);
        a.setEntityNo(entityNo);
        a.setUserNo(userNo);
        a.setIsOwner(true);
        a.setIsPrimary(true);
        a.setStatus(MchAccount.ACTIVE);
        DataScopeContext.executeWithoutScope(() -> accountMapper.insert(a));

        tokenMapper.delete(Wrappers.<MsgPushToken>lambdaQuery().eq(MsgPushToken::getClientId, clientId));
        MsgPushToken t = new MsgPushToken();
        t.setReceiverType(MsgMessage.RECEIVER_STAFF);
        t.setReceiverNo(userNo);
        t.setPlatform(MsgPushToken.APP_ANDROID);
        t.setProvider("GETUI");
        t.setClientId(clientId);
        tokenMapper.insert(t);
        return new Owner(userNo, entityNo);
    }

    private void drainOutbox() {
        for (int i = 0; i < 50 && dispatcher.pendingCount() > 0; i++) {
            dispatcher.dispatchPending();
        }
    }
}
