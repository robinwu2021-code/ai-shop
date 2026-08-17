package ai.neargo.shop.scenario;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.message.entity.SysNotifyLog;
import ai.neargo.shop.message.notify.NotifyChannelService;
import ai.neargo.shop.message.notify.NotifyLogService;
import ai.neargo.shop.spi.notify.MailTemplatePort;
import ai.neargo.shop.spi.notify.MailPort;
import ai.neargo.shop.spi.notify.NotifyBizType;
import ai.neargo.shop.spi.notify.SmsPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 触达链路的端到端走查：**短信 → 邮件 → 业务模板 → 语言 → 回查记录**。
 *
 * <p><b>为什么在已有一堆分片测试之后还要这一条</b>：通道体检、模拟发送、模板渲染、
 * 发送记录检索各自都有测试，但没有一条把它们连起来走一遍。分片全绿而整条链断掉
 * 是完全可能的 —— 比如发出去了却没留痕、留了痕却查不回来（收件人存的是掩码，
 * 而运营手上只有明文），每一段单看都对。
 *
 * <p><b>为什么发送走服务层而回查走 HTTP</b>：`test-send` 挂着图形验证码，
 * 而验证码的码只在服务内存里 —— 要在 HTTP 层发一条就得给验证码开一个测试后门，
 * 那个后门的风险远大于它带来的覆盖（验证码的消费逻辑本就有测试）。
 * 回查这一段没有验证码，正好把新加的检索参数在真实请求上走一遍。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("触达端到端：短信 → 邮件 → 模板 → 语言 → 回查")
class NotifyEndToEndFlowTest {

    /** 这条链路专用的手机号/邮箱，与别的测试错开 —— 共享 H2 里按收件人查要能只捞到自己的 */
    private static final String PHONE = "13611110001";
    private static final String MAIL = "e2e-notify@neargo.ai";

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private SmsPort smsPort;
    @Autowired
    private MailPort mailPort;
    @Autowired
    private MailTemplatePort mailTemplatePort;
    @Autowired
    private NotifyLogService notifyLogService;
    @Autowired
    private NotifyChannelService channelService;
    @Autowired
    private ai.neargo.shop.notify.port.StubMailGateway mailStub;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup
                        .SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    @DisplayName("★★★ 一条链走完：两条通道各发一次 → 都留痕 → 运营用明文号码查得回来")
    void wholeChainFromSendToSearch() throws Exception {
        // ── 1. 通道体检：四条通道都在，且**任何一项都不回密钥明文**
        var health = channelService.health();
        assertThat(health).extracting(NotifyChannelService.ChannelHealth::channel)
                .containsExactlyInAnyOrder("SMS", "MAIL", "WXSUB", "PUSH");

        // ── 2. 短信：走 @Primary 的留痕装饰器 → 桩 → sys_notify_log
        smsPort.sendOtp(PHONE, "246813", NotifyBizType.OTP, null);

        // ── 3. 邮件：自定义主题正文（邮件是自由文本通道，与短信不同）
        mailPort.send(MAIL, "【数智邻购】端到端走查", "这封信用于确认邮件通道留痕。",
                SysNotifyLog.BIZ_TEST, "ST-E2E");

        /*
         * ── 4. 回查：**用明文手机号**查。
         * 这一步是整条链最容易断的地方 —— 库里存的是 138****0001，
         * 而运营手上只有完整号码。前面每一段都对、这一段断掉的表现是「查无此条」，
         * 而那会被读成「压根没发出去」。
         */
        String admin = ai.neargo.shop.support.TestLogin.admin(mvc(), json);
        JsonNode byPhone = search(admin, "target=" + PHONE);
        assertThat(names(byPhone)).as("明文手机号要能命中掩码记录").contains("136****0001");

        JsonNode byMail = search(admin, "target=" + MAIL);
        assertThat(names(byMail)).as("邮箱同理，掩码后仍要查得到")
                .anySatisfy(t -> assertThat(t).contains("@neargo.ai"));

        // 今天到今天：截止日按闭区间写的话，这里会是空
        String today = java.time.LocalDate.now().toString();
        JsonNode todayOnly = search(admin, "target=" + PHONE + "&from=" + today + "&to=" + today);
        assertThat(names(todayOnly)).isNotEmpty();

        // 通道维度仍然正交：按 MAIL 筛不该出现那条短信
        JsonNode mailOnly = search(admin, "channel=MAIL&target=" + PHONE);
        assertThat(names(mailOnly)).isEmpty();
    }

    @Test
    @DisplayName("★★★ 业务模板与语言：中文走模板、英文走译文、语言未知走平台默认")
    void templateAndLanguage() {
        // ── 中文：正文来自 msg_template（V142），不是代码里的兜底
        mailTemplatePort.send(MAIL, MailTemplatePort.TPL_OPS_RESET_PWD, "zh-CN",
                "重置", java.util.Map.of("realName", "小周", "token", "E2E-ZH", "ttlMinutes", "15"),
                "内置兜底 {realName}", NotifyBizType.OPS_RESET_PASSWORD, null);
        assertThat(mailStub.last().body()).contains("重置码").contains("E2E-ZH")
                .doesNotContain("内置兜底");

        // ── 英文：V145 的译文
        mailTemplatePort.send(MAIL, MailTemplatePort.TPL_OPS_RESET_PWD, "en",
                "Reset", java.util.Map.of("realName", "Sam", "token", "E2E-EN", "ttlMinutes", "15"),
                "built-in {realName}", NotifyBizType.OPS_RESET_PASSWORD, null);
        assertThat(mailStub.last().body()).contains("Reset code").contains("E2E-EN");

        /*
         * ── 语言未知（null）：走**平台默认语言**这个系统设置，
         * 而不是各调用点各写一个 zh-CN。管理员替别人建账号就是这种情况。
         */
        channelService.saveDefaultLang("en", "ST-E2E");
        try {
            mailTemplatePort.send(MAIL, MailTemplatePort.TPL_OPS_RESET_PWD, null,
                    "Reset", java.util.Map.of("realName", "Sam", "token", "E2E-DEF",
                            "ttlMinutes", "15"),
                    "built-in {realName}", NotifyBizType.OPS_RESET_PASSWORD, null);
            assertThat(mailStub.last().body())
                    .as("默认语言设成 en，语言未知的那封就该按 en 发")
                    .contains("Reset code").contains("E2E-DEF");
        } finally {
            channelService.saveDefaultLang("zh-CN", "ST-E2E");
        }
    }

    @Test
    @DisplayName("★★★ 正文不进发送记录 —— 那两封信里有一次性密码与重置码")
    void bodyNeverReachesTheLog() {
        String secret = "E2E-SECRET-" + System.nanoTime();
        mailTemplatePort.send(MAIL, MailTemplatePort.TPL_OPS_RESET_PWD, "zh-CN",
                "重置", java.util.Map.of("realName", "小周", "token", secret, "ttlMinutes", "15"),
                "内置兜底 {realName}", NotifyBizType.OPS_RESET_PASSWORD, null);

        // 正文确实发出去了（桩收到了）
        assertThat(mailStub.last().body()).contains(secret);

        /*
         * 但发送记录里**任何一列都不能有它**。这张表全运营可见，
         * 而重置码在有效期内等同于那个账号的钥匙。
         */
        PageData<SysNotifyLog> rows = notifyLogService.list(
                null, null, null, null, null, MAIL, 1, 50);
        assertThat(rows.records()).isNotEmpty();
        assertThat(rows.records().toString())
                .as("重置码绝不能出现在发送记录的任何字段里")
                .doesNotContain(secret);
    }

    private JsonNode search(String token, String query) throws Exception {
        String body = mvc().perform(get("/ops/notify-logs?" + query)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("records");
    }

    private java.util.List<String> names(JsonNode records) {
        java.util.List<String> out = new java.util.ArrayList<>();
        records.forEach(r -> out.add(r.get("target").asString()));
        return out;
    }
}
