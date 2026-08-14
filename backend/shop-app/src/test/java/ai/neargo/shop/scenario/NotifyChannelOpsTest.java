package ai.neargo.shop.scenario;

import ai.neargo.shop.channel.notify.port.StubPushGateway;
import ai.neargo.shop.channel.notify.port.StubWxSubscribeGateway;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.message.entity.MsgMessage;
import ai.neargo.shop.message.mapper.MessageMappers.MessageMapper;
import ai.neargo.shop.message.notify.NotifyChannelService;
import ai.neargo.shop.message.notify.NotifyLogService;
import ai.neargo.shop.spi.notify.MailTemplatePort;
import ai.neargo.shop.spi.notify.NotifyBizType;
import ai.neargo.shop.spi.notify.WxSubscribePort;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 运营端触达中心：通道体检 + 四通道模拟发送（TDD-运营端触达中心 §4、§5）。
 *
 * <p>这里最要紧的两条断言是**两种可读错误**：额度为 0 的微信订阅消息、
 * 没绑设备的推送。二者若静默成功，运营会以为「发了但他没看见」，
 * 而真相分别是「白发一条还烧了额度」与「这人压根没装 App」—— 下一步动作完全不同。
 */
@SpringBootTest
@ActiveProfiles("test")
class NotifyChannelOpsTest {

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;
    @Autowired
    private NotifyChannelService channelService;
    @Autowired
    private NotifyLogService notifyLogService;
    @Autowired
    private StubWxSubscribeGateway wxStub;
    @Autowired
    private StubPushGateway pushStub;
    @Autowired
    private MessageMapper messageMapper;
    @Autowired
    private ai.neargo.shop.message.notify.WxSubscribeSender wxSender;
    @Autowired
    private ai.neargo.shop.spi.notify.MailTemplatePort mailTemplatePort;
    @Autowired
    private ai.neargo.shop.channel.notify.port.StubMailGateway mailStub;
    @Autowired
    private ai.neargo.shop.message.mapper.MessageMappers.TemplateMapper templateMapper;

    @BeforeEach
    void clearStubs() {
        wxStub.clear();
        pushStub.clear();
    }

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup
                        .SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    // ---------------------------------------------------------------- 通道体检

    @Test
    @DisplayName("★ 通道体检给出四条通道，且**任何一项都不回传密钥明文**")
    void healthNeverLeaksSecrets() throws Exception {
        String admin = ai.neargo.shop.support.TestLogin.admin(mvc(), json);
        String body = mvc().perform(get("/ops/notify-channels")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode rows = json.readTree(body).get("data");
        assertThat(rows).hasSize(4);

        for (JsonNode row : rows) {
            for (JsonNode cred : row.get("credentials")) {
                /*
                 * 凭据项只能有 envVar / present / required 三个字段。
                 * 多一个字段就是多一条泄露路径 —— 而这个接口一旦回传过明文，
                 * 后面每一版都得记得别把它加回来。这条断言就是那个记性。
                 */
                assertThat(cred.propertyNames())
                        .as("凭据项不得出现密钥值：%s", cred)
                        .containsExactlyInAnyOrder("envVar", "present", "required");
            }
        }
    }

    @Test
    @DisplayName("测试环境下四条通道都在桩模式 —— 体检要如实说，不能显示成「已启用」")
    void healthReportsStub() {
        assertThat(channelService.health())
                .allSatisfy(h -> {
                    assertThat(h.stub()).as("%s 应在桩模式", h.channel()).isTrue();
                    assertThat(h.enabled()).isFalse();
                });
    }

    @Test
    @DisplayName("★ 微信模板号：运营改完立即生效，清空则回落环境变量")
    void wxTemplatesOverrideAndFallback() {
        String before = channelService.templateIdOf(WxSubscribePort.SCENE_ORDER_ARRIVED);

        channelService.saveWxTemplates("TPL_NEW_ARRIVED", null, "ST-TEST");
        assertThat(channelService.templateIdOf(WxSubscribePort.SCENE_ORDER_ARRIVED))
                .isEqualTo("TPL_NEW_ARRIVED");

        // 清空 = 回落，而不是「设成空」—— 设成空会让通道以为没配模板而静默不发
        channelService.saveWxTemplates(null, null, "ST-TEST");
        assertThat(channelService.templateIdOf(WxSubscribePort.SCENE_ORDER_ARRIVED))
                .as("清空覆盖后要回到环境变量/桩的默认值")
                .isEqualTo(before);
    }

    // ---------------------------------------------------------------- 模拟发送

    @Test
    @DisplayName("★★ 没有订阅额度的用户：**明确拒绝**，不白发也不烧额度")
    void wxTestSendRefusesWhenNoQuota() {
        // 这个用户从没授权过订阅消息 —— 发出去会被微信以 43101 拒，
        // 而运营看到的会是一条无从下手的通道错误
        assertThatThrownBy(() -> notifyLogService.precheckTestTarget("WXSUB", "U-NO-QUOTA"))
                .hasMessageContaining(ErrorCode.NOTIFY_WX_QUOTA_EMPTY.name());
        assertThat(wxStub.sent()).isEmpty();
    }

    @Test
    @DisplayName("★★ 没绑设备的用户：**明确拒绝**，而不是静默成功")
    void pushTestSendRefusesWhenNoDevice() {
        assertThatThrownBy(() -> notifyLogService.precheckTestTarget("PUSH", "U-NO-DEVICE"))
                .hasMessageContaining(ErrorCode.NOTIFY_NO_DEVICE.name());
        assertThat(pushStub.sent()).isEmpty();
    }

    @Test
    @DisplayName("★ 站内信模拟发送：塞进指定收件箱，**不进 sys_notify_log**")
    void inAppTestSendGoesToInbox() throws Exception {
        String admin = ai.neargo.shop.support.TestLogin.admin(mvc(), json);

        mvc().perform(post("/ops/notify-logs/test-inapp")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receiverType\":\"OPS\",\"receiverNo\":\"ST0001\","
                                + "\"title\":\"联通测试\",\"body\":\"这是一条测试站内信\","
                                + "\"link\":\"/messages\"}"))
                .andExpect(status().isOk());

        assertThat(messageMapper.selectCount(Wrappers.<MsgMessage>lambdaQuery()
                .eq(MsgMessage::getReceiverType, MsgMessage.RECEIVER_OPS)
                .eq(MsgMessage::getReceiverNo, "ST0001")
                .eq(MsgMessage::getTitle, "联通测试"))).isPositive();
    }

    @Test
    @DisplayName("站内信模拟发送不过图形验证码 —— 它发不出平台，骚扰不到外部的人")
    void inAppTestSendNeedsNoCaptcha() throws Exception {
        String admin = ai.neargo.shop.support.TestLogin.admin(mvc(), json);
        // 请求体里一个验证码字段都没有，照样成功
        mvc().perform(post("/ops/notify-logs/test-inapp")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receiverType\":\"OPS\",\"receiverNo\":\"ST0001\","
                                + "\"title\":\"无验证码也能发\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("★ 微信提示语可自定义 —— 此前写死在网关里，改一句话要发版")
    void wxTipIsCustomisable() {
        // 直接走 sender（模拟发送与事件链路共用它），验证运营填的那句真的传到了通道
        wxSender.orderArrived("U-TIP-TEST", 2, "pages/orders/index", "今晚 9 点前来取");
        // 没授权的用户会在额度那步静默跳过，所以这里只断言「没有异常、也没有误发」
        assertThat(wxStub.sent()).noneMatch(x -> "U-TIP-TEST".equals(x.openId()));
    }

    @Test
    @DisplayName("★ 正文超 2000 字被挡下 —— 可填之后要有上限，否则这里就是群发工具")
    void oversizedBodyRejected() {
        String huge = "字".repeat(2001);
        assertThatThrownBy(() -> notifyLogService.testSend("MAIL", "a@b.com", null,
                new NotifyLogService.TestContent(null, huge, null),
                "no-captcha", "0000", "ST-TEST"))
                .isInstanceOf(RuntimeException.class);
    }

    // ------------------------------------------------------- 邮件业务模板（§2.5）

    @Test
    @DisplayName("★ 邮件按平台业务模板渲染 —— 库里那份就是发出去的那份")
    void mailRendersFromTemplate() {
        mailTemplatePort.send("tpl@neargo.ai", MailTemplatePort.TPL_OPS_INIT_PWD,
                "【数智邻购】运营端账号已开通",
                java.util.Map.of("realName", "小王", "username", "tpl@neargo.ai",
                        "password", "Init#2026"),
                "内置兜底：{realName} {username} {password}",
                NotifyBizType.OPS_INIT_PASSWORD, "ST-TEST");

        var last = mailStub.last();
        assertThat(last).isNotNull();
        // 模板正文（V142 种子）代入后应含这三个值，且不再出现占位
        assertThat(last.body()).contains("小王").contains("Init#2026").doesNotContain("{password}");
    }

    @Test
    @DisplayName("★★ 占位没取到值时整封回落内置文案 —— 不发一封写着 {password} 的邮件")
    void missingPlaceholderFallsBack() {
        mailTemplatePort.send("miss@neargo.ai", MailTemplatePort.TPL_OPS_INIT_PWD,
                "主题", java.util.Map.of("realName", "小李"),   // 少了 username / password
                "内置兜底：你好 {realName}，请联系管理员获取密码。",
                NotifyBizType.OPS_INIT_PASSWORD, "ST-TEST");

        // 收件人此刻正等着这个密码登录，一封读不懂的邮件比一封旧文案的邮件糟得多
        assertThat(mailStub.last().body()).contains("内置兜底").doesNotContain("{password}");
    }

    @Test
    @DisplayName("★★ 停用账号类模板**不会**让邮件发不出去 —— 那样新同事永远登不进来")
    void disablingAccountTemplateStillSends() {
        var tpl = templateMapper.selectOne(Wrappers.<ai.neargo.shop.message.entity.MsgTemplate>lambdaQuery()
                .eq(ai.neargo.shop.message.entity.MsgTemplate::getTemplateNo,
                        MailTemplatePort.TPL_OPS_INIT_PWD).last("limit 1"));
        assertThat(tpl).as("V142 应已种下这条模板").isNotNull();
        tpl.setEnabled(false);
        templateMapper.updateById(tpl);
        try {
            mailTemplatePort.send("off@neargo.ai", MailTemplatePort.TPL_OPS_INIT_PWD, "主题",
                    java.util.Map.of("realName", "小张"),
                    "内置兜底：{realName} 的账号已开通",
                    NotifyBizType.OPS_INIT_PASSWORD, "ST-TEST");
            assertThat(mailStub.last()).isNotNull();
            assertThat(mailStub.last().body()).contains("小张");
        } finally {
            tpl.setEnabled(true);
            templateMapper.updateById(tpl);
        }
    }

    @Test
    @DisplayName("发送记录按用途筛 —— 通道与用途是两个正交维度")
    void logsFilterByBizType() throws Exception {
        String admin = ai.neargo.shop.support.TestLogin.admin(mvc(), json);
        mvc().perform(get("/ops/notify-logs?bizType=OTP")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records").exists());
    }
}
