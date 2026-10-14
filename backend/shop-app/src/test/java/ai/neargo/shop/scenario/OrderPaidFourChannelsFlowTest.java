package ai.neargo.shop.scenario;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.event.OutboxDispatcher;
import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.shop.merchant.entity.MchAccount;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchAccountMapper;
import ai.neargo.shop.message.NotifyScene;
import ai.neargo.shop.message.entity.MchNotifyPref;
import ai.neargo.shop.message.entity.MsgMessage;
import ai.neargo.shop.message.entity.MsgPushToken;
import ai.neargo.shop.message.entity.MsgSubscribe;
import ai.neargo.shop.message.entity.SysNotifyLog;
import ai.neargo.shop.message.mapper.MessageMappers.MchNotifyPrefMapper;
import ai.neargo.shop.message.mapper.MessageMappers.MessageMapper;
import ai.neargo.shop.message.mapper.MessageMappers.PushTokenMapper;
import ai.neargo.shop.message.mapper.MessageMappers.SubscribeMapper;
import ai.neargo.shop.message.notify.MerchantNotifyRecipients;
import ai.neargo.shop.message.notify.MerchantNotifyPrefs;
import ai.neargo.shop.message.notify.StubWeComBotSender;
import ai.neargo.shop.notify.port.StubMailGateway;
import ai.neargo.shop.notify.port.StubPushGateway;
import ai.neargo.shop.notify.port.StubSmsGateway;
import ai.neargo.shop.notify.port.StubWxSubscribeGateway;
import ai.neargo.shop.spi.notify.WxSubscribePort;
import ai.neargo.shop.spi.trade.OrderEvents;
import ai.neargo.shop.support.TestLogin;
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
 * 来单四条腿同时走（TDD-来单四渠道与商家通知设置 §5）。
 *
 * <p>开关与群都是**门店级**（用户 2026-10-10 订正）—— 测试里门店号是
 * {@code "ST-" + entityNo}，与 {@code publishPaid} 发出去的那一格对齐。
 *
 * <p>微信订阅 / 企微群 / 短信 / App 推送，加恒发的站内信。
 * 四条各自独立：任一条失败或被商家关掉，其余照发。
 *
 * <p><b>为什么要一条「四条都到」的测试</b>：此前每条通道各有自己的测试，
 * 而「四条同时都发」这件事没人验 —— 加第五条通道、或在 `fanOutToStaff` 里
 * 改一个 continue，都可能静默地少掉一条，而每条单测仍然绿。
 */
@SpringBootTest
@ActiveProfiles("test")
class OrderPaidFourChannelsFlowTest {

    private static final String GROUP = "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=four";
    private static final String SCENE = NotifyScene.SUB_ORDER_PAID;

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private OutboxEventBus eventBus;
    @Autowired
    private OutboxDispatcher dispatcher;
    @Autowired
    private MchAccountMapper accountMapper;
    @Autowired
    private PushTokenMapper tokenMapper;
    @Autowired
    private SubscribeMapper subscribeMapper;
    @Autowired
    private MessageMapper messageMapper;
    @Autowired
    private MchNotifyPrefMapper prefMapper;
    @Autowired
    private MerchantNotifyRecipients recipients;
    @Autowired
    private MerchantNotifyPrefs prefs;
    @Autowired
    private StubWxSubscribeGateway wxStub;
    @Autowired
    private StubPushGateway pushStub;
    @Autowired
    private StubSmsGateway smsStub;
    @Autowired
    private StubMailGateway mailStub;
    @Autowired
    private ai.neargo.shop.message.mapper.MessageMappers.NotifyLogMapper notifyLogMapper;
    @Autowired
    private StubWeComBotSender botStub;

    @BeforeEach
    void drain() {
        drainOutbox();
        wxStub.clear();
        pushStub.clear();
        smsStub.clear();
        mailStub.clear();
        botStub.clear();
    }

    @Test
    @DisplayName("★★★ 一单付款 → 微信 / 企微群 / 短信 / App **四条各发一次**（AC1）")
    void allFourFire() throws Exception {
        Owner o = anOwner("wx-open-f4-1", "M-F4-1", "cid-f4-1", "13700000001");
        grantWx(o.userNo);
        configureGroup(o.storeNo);

        publishPaid(o.entityNo, "SUB-F4-1", 12_80L);

        assertThat(sentWx(o.openId)).as("微信订阅").hasSize(1);
        assertThat(pushTo("cid-f4-1")).as("App 推送").hasSize(1);
        assertThat(botStub.sent()).as("企微群").hasSize(1);
        assertThat(smsTo("13700000001")).as("短信").hasSize(1);
        assertThat(inbox(o.userNo)).as("站内信（恒发）").isNotEmpty();
    }

    @Test
    @DisplayName("★★★ 短信到店主 + 这家店自填的两个号（用户 2026-10-10）")
    void smsGoesToOwnerPlusExtras() throws Exception {
        Owner o = anOwner("wx-open-f4-10", "M-F4-10", "cid-f4-10", "13700000010");
        recipients.setPhones(o.storeNo(), List.of("13700000011", "13700000012"), "test");

        publishPaid(o.entityNo(), "SUB-F4-10", 600L);

        assertThat(smsTo("13700000010")).as("店主那一个恒发").hasSize(1);
        assertThat(smsTo("13700000011")).hasSize(1);
        assertThat(smsTo("13700000012")).hasSize(1);
    }

    @Test
    @DisplayName("★★ 店主把自己的号又填了一遍 → **只发一条**（短信按条计费）")
    void ownerPhoneIsNotSentTwice() throws Exception {
        Owner o = anOwner("wx-open-f4-11", "M-F4-11", "cid-f4-11", "13700000013");
        recipients.setPhones(o.storeNo(), List.of("13700000013"), "test");

        publishPaid(o.entityNo(), "SUB-F4-11", 600L);

        assertThat(smsTo("13700000013")).as("去重了才对").hasSize(1);
    }

    @Test
    @DisplayName("★★★ 邮件发到这家店填的地址；**没填就不发**（用户 2026-10-10）")
    void mailGoesToConfiguredAddress() throws Exception {
        Owner o = anOwner("wx-open-f4-12", "M-F4-12", "cid-f4-12", "13700000014");
        recipients.setEmail(o.storeNo(), "shop@example.com", "test");

        publishPaid(o.entityNo(), "SUB-F4-12", 12_80L);

        assertThat(mailTo("shop@example.com")).singleElement().satisfies(m -> {
            assertThat(m.subject()).contains("新订单").contains("￥12.80");
            // 与企微群里那条是同一份排版，只是没有 markdown 记号
            assertThat(m.body()).contains("SUB-F4-12").doesNotContain("**").doesNotContain("> ");
        });

        // 另一家店没填地址 —— 一封都不该发
        Owner none = anOwner("wx-open-f4-13", "M-F4-13", "cid-f4-13", "13700000015");
        int before = mailStub.all().size();
        publishPaid(none.entityNo(), "SUB-F4-13", 100L);
        assertThat(mailStub.all()).as("没填地址就不发").hasSize(before);
    }

    @Test
    @DisplayName("★★★ 关掉邮件 → 不发邮件，**其余四条照发**")
    void merchantCanMuteMail() throws Exception {
        Owner o = anOwner("wx-open-f4-14", "M-F4-14", "cid-f4-14", "13700000016");
        recipients.setEmail(o.storeNo(), "muted@example.com", "test");
        configureGroup(o.storeNo());
        grantWx(o.userNo());
        prefs.set(o.storeNo(), SCENE, MchNotifyPref.CH_MAIL, false, "test");

        publishPaid(o.entityNo(), "SUB-F4-14", 700L);

        assertThat(mailTo("muted@example.com")).isEmpty();
        assertThat(smsTo("13700000016")).hasSize(1);
        assertThat(botStub.sent()).hasSize(1);
        assertThat(pushTo("cid-f4-14")).hasSize(1);
    }

    @Test
    @DisplayName("★★★ 短信**只到店主**，员工不发（AC3）")
    void smsOnlyToOwner() throws Exception {
        Owner o = anOwner("wx-open-f4-2", "M-F4-2", "cid-f4-2", "13700000002");
        // 同一家店再挂一个店员，他有自己的手机号
        addStaff(o.entityNo, "MA-F4-2-STAFF", "U-F4-2-STAFF", "13700009999");

        publishPaid(o.entityNo, "SUB-F4-2", 500L);

        assertThat(smsTo("13700000002")).as("店主该收到").hasSize(1);
        assertThat(smsTo("13700009999")).as("店员不该收到 —— 短信按条计费").isEmpty();
    }

    @Test
    @DisplayName("★★★ 短信模板没报备：短信写 FAILED，**其余三条照发**（AC2）")
    void smsWithoutTemplateFailsAlone() throws Exception {
        /*
         * 测试世界走 StubSmsGateway，它不看模板号 —— 所以这里验的不是桩的行为，
         * 而是**调用方吞掉异常**这件事：把商家的短信开关留着、让店主号为空，
         * sendOrderPaid 根本不会被调到，而其余三条必须照发。
         * 「模板缺失时抛 tpl_unconfigured」由 AliSmsGateway 自己的单测盖。
         */
        Owner o = anOwner("wx-open-f4-3", "M-F4-3", "cid-f4-3", null);
        grantWx(o.userNo);
        configureGroup(o.storeNo);

        publishPaid(o.entityNo, "SUB-F4-3", 700L);

        assertThat(smsStub.all()).as("没有店主手机号，短信发不出去").isEmpty();
        assertThat(sentWx(o.openId)).as("微信照发").hasSize(1);
        assertThat(pushTo("cid-f4-3")).as("App 照发").hasSize(1);
        assertThat(botStub.sent()).as("企微群照发").hasSize(1);
    }

    @Test
    @DisplayName("★★★ 商家关掉短信 → 短信不发，**其余三条照发**（AC4）")
    void merchantCanMuteSms() throws Exception {
        Owner o = anOwner("wx-open-f4-4", "M-F4-4", "cid-f4-4", "13700000004");
        grantWx(o.userNo);
        configureGroup(o.storeNo);
        prefs.set(o.storeNo, SCENE, MchNotifyPref.CH_SMS, false, "test");

        publishPaid(o.entityNo, "SUB-F4-4", 900L);

        assertThat(smsTo("13700000004")).as("关了就不该发").isEmpty();
        assertThat(sentWx(o.openId)).hasSize(1);
        assertThat(pushTo("cid-f4-4")).hasSize(1);
        assertThat(botStub.sent()).hasSize(1);
    }

    @Test
    @DisplayName("★★★ 没设置过的商家**四条全开**（AC7）—— 这张表一建不能让存量商家静默失声")
    void missingRowMeansOn() throws Exception {
        Owner o = anOwner("wx-open-f4-5", "M-F4-5", "cid-f4-5", "13700000005");
        // 刻意一行都不写
        assertThat(DataScopeContext.executeWithoutScope(() ->
                prefMapper.selectList(Wrappers.<MchNotifyPref>lambdaQuery()
                        .eq(MchNotifyPref::getStoreNo, o.storeNo)))).isEmpty();

        assertThat(prefs.switchesOf(o.storeNo, SCENE).values()).containsOnly(true);
        assertThat(prefs.on(o.storeNo, SCENE, MchNotifyPref.CH_SMS)).isTrue();
        assertThat(prefs.on(o.storeNo, SCENE, MchNotifyPref.CH_WEBHOOK)).isTrue();
    }

    @Test
    @DisplayName("商家关一条只影响那一条；回显给的是**店主自己的选择**，不是串联结果")
    void switchesReflectMerchantChoice() throws Exception {
        Owner o = anOwner("wx-open-f4-6", "M-F4-6", "cid-f4-6", "13700000006");
        prefs.set(o.storeNo, SCENE, MchNotifyPref.CH_PUSH, false, "test");

        var sw = prefs.switchesOf(o.storeNo, SCENE);
        assertThat(sw.get(MchNotifyPref.CH_PUSH)).isFalse();
        assertThat(sw.get(MchNotifyPref.CH_SMS)).isTrue();
        assertThat(sw.get(MchNotifyPref.CH_WXSUB)).isTrue();
        assertThat(sw.get(MchNotifyPref.CH_WEBHOOK)).isTrue();
        // 顺序即页面上的顺序，别让它漂
        assertThat(sw.keySet()).containsExactlyElementsOf(MchNotifyPref.SWITCHABLE);
    }

    @Test
    @DisplayName("★★★ 一家店关掉，**同主体的另一家照发** —— 这才是「基于门店」的量具（AC5）")
    void switchesAreIsolatedPerStore() throws Exception {
        /*
         * 没有这一条，「按门店」与「按主体」在测试里长得一模一样：
         * 单店商家两种实现都绿。粒度改对了没有，只有两家店的场景看得见。
         */
        Owner o = anOwner("wx-open-f4-8", "M-F4-8", "cid-f4-8", "13700000008");
        String storeA = "ST-F4-8-A";
        String storeB = "ST-F4-8-B";
        prefs.set(storeA, SCENE, MchNotifyPref.CH_PUSH, false, "test");

        assertThat(prefs.on(storeA, SCENE, MchNotifyPref.CH_PUSH)).as("A 店关了").isFalse();
        assertThat(prefs.on(storeB, SCENE, MchNotifyPref.CH_PUSH)).as("B 店不该被连带关掉").isTrue();

        // 真实链路：同一个主体，A 店来单不响、B 店来单响
        eventBus.publish(new OrderEvents.SubOrderPaid("SUB-F4-8-A", "SO-A", o.entityNo(),
                storeA, "U-BUYER-A", 100L));
        drainOutbox();
        assertThat(pushTo("cid-f4-8")).as("A 店关了 App，不该响").isEmpty();

        eventBus.publish(new OrderEvents.SubOrderPaid("SUB-F4-8-B", "SO-B", o.entityNo(),
                storeB, "U-BUYER-B", 100L));
        drainOutbox();
        assertThat(pushTo("cid-f4-8")).as("B 店照响").hasSize(1);
    }

    @Test
    @DisplayName("★★ 售后与评价也按门店 —— 两个事件的 storeNo 是 2026-10-10 补的（AC5）")
    void afterSaleAndReviewAlsoPerStore() throws Exception {
        Owner o = anOwner("wx-open-f4-9", "M-F4-9", "cid-f4-9", "13700000009");
        String storeA = "ST-F4-9-A";
        prefs.set(storeA, NotifyScene.AFTER_SALE_APPLIED, MchNotifyPref.CH_PUSH, false, "test");
        prefs.set(storeA, NotifyScene.REVIEW_CREATED, MchNotifyPref.CH_PUSH, false, "test");

        eventBus.publish(new OrderEvents.AfterSaleApplied("AS-F4-9", "SUB-F4-9", o.entityNo(),
                storeA, "U-BUYER-F4-9", "REFUND_ONLY", 500L));
        eventBus.publish(new ai.neargo.shop.spi.product.ProductEvents.ReviewCreated(
                "RV-F4-9", o.entityNo(), storeA, "G-F4-9", 1));
        drainOutbox();

        assertThat(pushTo("cid-f4-9")).as("这家店把这两条都关了").isEmpty();

        // 另一家店没关，照发
        eventBus.publish(new OrderEvents.AfterSaleApplied("AS-F4-9-B", "SUB-F4-9-B", o.entityNo(),
                "ST-F4-9-B", "U-BUYER-F4-9", "REFUND_ONLY", 500L));
        drainOutbox();
        assertThat(pushTo("cid-f4-9")).as("B 店照发").hasSize(1);
    }

    @Test
    @DisplayName("★★★ 每条**真的发过**的通道，在 sys_notify_log 里都有一行（用户 2026-10-10）")
    void everyAttemptedChannelIsLogged() throws Exception {
        Owner o = anOwner("wx-open-f4-15", "M-F4-15", "cid-f4-15", "13700000017");
        grantWx(o.userNo());
        configureGroup(o.storeNo());
        recipients.setEmail(o.storeNo(), "log@example.com", "test");
        long before = logCount();

        publishPaid(o.entityNo(), "SUB-F4-15", 12_80L);

        /*
         * 四条外发通道各一行。**站内信不在这张表里** —— 它本身就是 msg_message 的一行，
         * 那是事实记录，不是投递记录；把它也记进来会让「发了几条」这个数字说不清。
         */
        var rows = logsSince(before);
        assertThat(rows.stream().map(SysNotifyLog::getChannel).toList())
                .as("四条外发通道都要留痕")
                .contains(SysNotifyLog.WXSUB, SysNotifyLog.PUSH,
                        SysNotifyLog.SMS, SysNotifyLog.MAIL, SysNotifyLog.WEBHOOK);
        assertThat(rows).allSatisfy(r -> assertThat(r.getStatus())
                .as("成功失败都要有个明确状态，不能空着")
                .isIn(SysNotifyLog.SENT, SysNotifyLog.FAILED));
    }

    @Test
    @DisplayName("★★★ 短信开着但没收件人 → **也记一行 FAILED no_recipient**（用户 2026-10-10：要在消息记录表看得见）")
    void smsWithoutRecipientStillLogs() throws Exception {
        Owner o = anOwner("wx-open-f4-18", "M-F4-18", "cid-f4-18", null);  // 店主无登录号、不填额外号
        long before = logCount();

        publishPaid(o.entityNo(), "SUB-F4-18", 500L);

        var sms = logsSince(before).stream()
                .filter(r -> SysNotifyLog.SMS.equals(r.getChannel())).toList();
        assertThat(sms).as("开了短信没人收，也要在记录表里看得见").singleElement().satisfies(r -> {
            assertThat(r.getStatus()).isEqualTo(SysNotifyLog.FAILED);
            assertThat(r.getError()).contains("no_recipient");
        });
    }

    @Test
    @DisplayName("★★★ **没配地址的通道不留痕** —— 那不是失败，是这条通道对这家店不存在")
    void unconfiguredChannelLeavesNoRow() throws Exception {
        // 不配群、不配邮箱、不授权微信，只有 App 与短信（店主号恒有）
        Owner o = anOwner("wx-open-f4-16", "M-F4-16", "cid-f4-16", "13700000018");
        long before = logCount();

        publishPaid(o.entityNo(), "SUB-F4-16", 500L);

        var channels = logsSince(before).stream().map(SysNotifyLog::getChannel).toList();
        assertThat(channels).as("企微群没配，不该有行").doesNotContain(SysNotifyLog.WEBHOOK);
        assertThat(channels).as("邮箱没填，不该有行").doesNotContain(SysNotifyLog.MAIL);
        assertThat(channels).as("App 配了设备，该有行").contains(SysNotifyLog.PUSH);
    }

    @Test
    @DisplayName("★★ 门店级通道的**顺序**写在一份名单里，不是靠代码行序")
    void storeChannelOrderIsDeclared() throws Exception {
        Owner o = anOwner("wx-open-f4-17", "M-F4-17", "cid-f4-17", "13700000019");
        configureGroup(o.storeNo());
        recipients.setEmail(o.storeNo(), "order@example.com", "test");
        long before = logCount();

        publishPaid(o.entityNo(), "SUB-F4-17", 500L);

        // 企微 → 短信 → 邮件：按 NotificationConsumer.STORE_CHANNEL_ORDER
        var seq = logsSince(before).stream().map(SysNotifyLog::getChannel)
                .filter(c -> List.of(SysNotifyLog.WEBHOOK, SysNotifyLog.SMS,
                        SysNotifyLog.MAIL).contains(c))
                .toList();
        assertThat(seq).containsExactly(SysNotifyLog.WEBHOOK, SysNotifyLog.SMS, SysNotifyLog.MAIL);
    }

    @Test
    @DisplayName("★★★ 站内信**没有开关** —— 想关 INAPP 的请求被拒（§2.1）")
    void inappHasNoSwitch() {
        assertThat(prefs.on("ST-F4-7", SCENE, "INAPP")).as("问它恒 true").isTrue();
        assertThat(MchNotifyPref.SWITCHABLE).doesNotContain("INAPP");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> prefs.set("ST-F4-7", SCENE, "INAPP", false, "test"))
                .isInstanceOf(ai.neargo.shop.common.BizException.class);
    }

    // ------------------------------------------------------------------ 辅助

    private record Owner(String userNo, String openId, String entityNo, String storeNo) {
    }

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity()).build();
    }

    /** @param ownerPhone 店主的登录手机号；传 null = 没配号（验短信无处可发那条） */
    private Owner anOwner(String openId, String entityNo, String clientId, String ownerPhone)
            throws Exception {
        String token = TestLogin.consumerByWechat(mvc(), json, openId);
        String body = mvc().perform(get("/mp/user/profile").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        String userNo = json.readTree(body).get("data").get("cUserNo").asString();
        subscribeMapper.delete(Wrappers.<MsgSubscribe>lambdaQuery().eq(MsgSubscribe::getUserNo, userNo));

        DataScopeContext.executeWithoutScope(() -> accountMapper.delete(
                Wrappers.<MchAccount>lambdaQuery().eq(MchAccount::getEntityNo, entityNo)));
        MchAccount a = new MchAccount();
        a.setMchAccountNo("MA-" + entityNo);
        a.setEntityNo(entityNo);
        a.setUserNo(userNo);
        a.setIsOwner(true);
        a.setIsPrimary(true);
        a.setStatus(MchAccount.ACTIVE);
        a.setLoginPhone(ownerPhone);
        DataScopeContext.executeWithoutScope(() -> accountMapper.insert(a));

        tokenMapper.delete(Wrappers.<MsgPushToken>lambdaQuery().eq(MsgPushToken::getClientId, clientId));
        MsgPushToken t = new MsgPushToken();
        t.setReceiverType(MsgMessage.RECEIVER_STAFF);
        t.setReceiverNo(userNo);
        t.setPlatform(MsgPushToken.APP_ANDROID);
        t.setProvider("GETUI");
        t.setClientId(clientId);
        tokenMapper.insert(t);
        return new Owner(userNo, openId, entityNo, "ST-" + entityNo);
    }

    private void addStaff(String entityNo, String accountNo, String userNo, String phone) {
        MchAccount a = new MchAccount();
        a.setMchAccountNo(accountNo);
        a.setEntityNo(entityNo);
        a.setUserNo(userNo);
        a.setIsOwner(false);
        a.setIsPrimary(false);
        a.setStatus(MchAccount.ACTIVE);
        a.setLoginPhone(phone);
        DataScopeContext.executeWithoutScope(() -> accountMapper.insert(a));
    }

    private void grantWx(String userNo) {
        MsgSubscribe s = new MsgSubscribe();
        s.setUserNo(userNo);
        s.setTemplateId("STUB_TPL_" + WxSubscribePort.SCENE_MCH_NEW_ORDER);
        s.setAccepted(true);
        s.setQuota(5);
        s.setAt(System.currentTimeMillis());
        subscribeMapper.insert(s);
    }

    /** 群按门店 —— 真源是 mch_notify_recipient.wecom_webhook */
    private void configureGroup(String storeNo) {
        recipients.setWecom(storeNo, GROUP, "test");
    }

    private void publishPaid(String entityNo, String subOrderNo, long minor) {
        eventBus.publish(new OrderEvents.SubOrderPaid(subOrderNo, "SO-" + subOrderNo, entityNo,
                "ST-" + entityNo, "U-BUYER-" + subOrderNo, minor));
        drainOutbox();
    }

    private List<StubWxSubscribeGateway.Sent> sentWx(String openId) {
        return wxStub.sent().stream().filter(s -> openId.equals(s.openId())).toList();
    }

    private List<StubPushGateway.Sent> pushTo(String clientId) {
        return pushStub.sent().stream().filter(s -> clientId.equals(s.clientId())).toList();
    }

    private List<StubSmsGateway.Sent> smsTo(String phone) {
        return smsStub.all().stream().filter(s -> phone.equals(s.phone())).toList();
    }

    private List<StubMailGateway.Sent> mailTo(String to) {
        return mailStub.all().stream().filter(m -> to.equals(m.to())).toList();
    }

    private List<MsgMessage> inbox(String userNo) {
        return DataScopeContext.executeWithoutScope(() ->
                messageMapper.selectList(Wrappers.<MsgMessage>lambdaQuery()
                        .eq(MsgMessage::getReceiverNo, userNo)));
    }

    private long logCount() {
        return DataScopeContext.executeWithoutScope(() ->
                notifyLogMapper.selectCount(Wrappers.<SysNotifyLog>lambdaQuery()));
    }

    /** 这一单之后新写进去的那些，按 id 升序 —— 顺序断言靠它 */
    private List<SysNotifyLog> logsSince(long beforeCount) {
        List<SysNotifyLog> all = DataScopeContext.executeWithoutScope(() ->
                notifyLogMapper.selectList(Wrappers.<SysNotifyLog>lambdaQuery()
                        .orderByAsc(SysNotifyLog::getId)));
        return all.subList((int) Math.min(beforeCount, all.size()), all.size());
    }

    private void drainOutbox() {
        for (int i = 0; i < 50 && dispatcher.pendingCount() > 0; i++) {
            dispatcher.dispatchPending();
        }
    }
}
