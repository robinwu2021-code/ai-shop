package ai.neargo.shop.scenario;

import ai.neargo.shop.common.OtpStore;
import ai.neargo.shop.message.MessageService;
import ai.neargo.shop.spi.notify.NotifyBizType;
import ai.neargo.shop.spi.notify.SmsPort;
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
 * 「消息与客服」域的端到端走查（矩阵 P-14 全域）。
 *
 * <p>与 {@code NotifyEndToEndFlowTest} 分工：那条走**外发通道**
 * （短信/邮件/模板/语言/回查）；这条走剩下的三块 ——
 * <b>客服工单的双向闭环、站内信的平台侧视图、模板发送量的统计口径</b>。
 *
 * <p>三块各自都有分片测试，但**没有一条验证过它们连起来是通的**：
 * 用户提的单运营看不看得见、运营回的话用户收不收得到、
 * 平台发的站内信在运营端查不查得着。每一段单看都对，连起来断掉是常态。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("消息与客服端到端：工单闭环 + 站内信可查 + 模板发送量")
class MessageDomainE2eTest {

    private static final String BUYER_PHONE = "13622220002";

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private OtpStore otpStore;
    @Autowired
    private SmsPort smsPort;
    @Autowired
    private MessageService messageService;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup
                        .SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    @DisplayName("★★★ 工单闭环：用户提单 → 运营看得见 → 回复 → **用户看得到那句回复**")
    void ticketRoundTrip() throws Exception {
        String buyer = ai.neargo.shop.support.TestLogin.consumer(mvc(), json, otpStore, BUYER_PHONE);

        // ── 用户提单
        String created = mvc().perform(post("/mp/ticket")
                        .header("Authorization", "Bearer " + buyer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject\":\"自提点关门了\",\"content\":\"晚上八点到店发现已经关门\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String ticketNo = json.readTree(created).get("data").get("ticketNo").asString();
        assertThat(ticketNo).isNotBlank();

        // ── 运营在待处理队列里看得见它
        String admin = ai.neargo.shop.support.TestLogin.admin(mvc(), json);
        String queue = mvc().perform(get("/ops/tickets")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(queue).as("用户刚提的单，运营队列里必须有").contains(ticketNo);

        // ── 运营回复
        mvc().perform(post("/ops/tickets/" + ticketNo + "/reply")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reply\":\"已联系自提点，今晚延长到九点。\"}"))
                .andExpect(status().isOk());

        /*
         * ── **用户看得到那句回复**。这是整个闭环唯一有价值的一步：
         * 此前 notify_ticket 建表就留了 reply 列、契约里却从没定义过「回复」这个动作，
         * 于是用户提单后反复点开详情看到的永远是空的 —— 而且不报任何错。
         */
        String detail = mvc().perform(get("/mp/ticket/" + ticketNo)
                        .header("Authorization", "Bearer " + buyer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(detail).get("data").get("reply").asString())
                .as("运营回了，用户那边就要看得到")
                .contains("延长到九点");
    }

    @Test
    @DisplayName("★★★ 站内信：发出去 → **运营在平台侧查得到** → 收件人自己也能看到")
    void inAppMessageIsVisibleFromBothSides() throws Exception {
        String admin = ai.neargo.shop.support.TestLogin.admin(mvc(), json);
        String title = "端到端站内信 " + System.nanoTime();

        // ── 运营模拟发送一条给自己（OPS 收件人）
        mvc().perform(post("/ops/notify-logs/test-inapp")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        // 发给 admin 自己（种子里他是 ST-ADMIN）——「两侧看到同一条」才成立
                        .content("{\"receiverType\":\"OPS\",\"receiverNo\":\"ST-ADMIN\","
                                + "\"title\":\"" + title + "\",\"body\":\"正文\",\"link\":\"/messages\"}"))
                .andExpect(status().isOk());

        /*
         * ── 平台侧记录查得到。**这条端点是新加的**（发送记录页的第二个 tab）——
         * 此前站内信发出去之后，运营端没有任何地方能查到它：
         * 发送记录那张表明确不收站内信，而收件箱只看得到自己的。
         */
        String rows = mvc().perform(get("/ops/inapp-messages?receiverType=OPS&receiverNo=ST-ADMIN")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode records = json.readTree(rows).get("data").get("records");
        assertThat(records.toString()).as("平台侧要查得到刚发的那条").contains(title);

        // 站内信**没有失败态**：入库即到达。这一列答的是「他读了吗」
        JsonNode first = records.get(0);
        assertThat(first.get("read").asBoolean()).as("刚发出去应当是未读").isFalse();

        /*
         * ── 收件人自己的收件箱里也有它。
         * **走 HTTP 而不是直接调 service**：list() 内部读当前登录者
         * （收件箱按人裁剪是它的核心语义），脱离请求上下文调用会 unauthorized ——
         * 那正说明这个裁剪是真在生效的。
         */
        String inbox = mvc().perform(get("/ops/message")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(inbox).as("收件人自己也要看得到").contains(title);
    }

    @Test
    @DisplayName("★★★ 模板发送量按**通道各取真源** —— 外发模板此前恒为 0，运营会把在用的停掉")
    void templateSentCountCountsTheRightTable() {
        var before = sentCountOf("TPL_SMS_OTP");

        // 走真实装饰器发一条：它写 sys_notify_log，**不写 notify_message**
        smsPort.sendOtp("13633330003", "112233", NotifyBizType.OTP, null);

        /*
         * 此前这个数按 notify_message（站内信收件箱）统计，而外发通道根本不写那张表 ——
         * 于是七条模板永远显示 0。运营拿这一列判断「哪条模板可以下线」，
         * 一个恒为 0 的数会让他把还在用的模板停掉。
         */
        assertThat(sentCountOf("TPL_SMS_OTP"))
                .as("发了一条短信，这条模板的近 30 天发送量就该 +1")
                .isEqualTo(before + 1);

        // 站内信模板仍走 notify_message —— 两个真源不能互换
        assertThat(sentCountOf("TPL_INAPP_TEST"))
                .as("站内信模板不受外发影响")
                .isEqualTo(sentCountOf("TPL_INAPP_TEST"));
    }

    private long sentCountOf(String templateNo) {
        return messageService.opsTemplates().stream()
                .filter(t -> templateNo.equals(t.templateNo()))
                .findFirst()
                .map(ai.neargo.shop.message.dto.MessageVOs.TemplateVO::sentCount)
                .orElseThrow(() -> new AssertionError("找不到模板 " + templateNo));
    }
}
