package ai.neargo.shop.scenario;

import ai.neargo.shop.message.entity.MsgMessage;
import ai.neargo.shop.support.TestLogin;
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
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 有人报名了 → 运营立刻知道（TDD-入驻意向与运营触达 §批 3/4）。
 *
 * <p><b>这一组盯的是「通知真的进了收件箱」</b>，不是「报名接口返回了 code=0」——
 * 通知这条链路的失败没有任何症状：报名照样成功、页面照样跳转，
 * 只有运营端一直没有新消息，而那看起来和「最近没人报名」一模一样。
 *
 * <p><b>企微那一条在测试里不发</b>：{@code shop.notify.wecom.webhook} 默认空，
 * {@code WeComBotSender.available()} 为 false。**而这正是要测的那一半** ——
 * 没配 webhook 时站内消息必须照发（[[default-off-is-the-untested-half]]）。
 */
@SpringBootTest
@ActiveProfiles("test")
class MerchantApplyOpsAlertFlowTest {

    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;
    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private ai.neargo.shop.message.mapper.MessageMappers.MessageMapper messageMapper;
    @Autowired
    private ai.neargo.shop.platform.mapper.PlatformMappers.StaffMapper staffMapper;
    @Autowired
    private ai.neargo.shop.message.notify.WeComBotSender weComBotSender;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    @DisplayName("★★★ 报名落库后，每个在用的运营各收到一条站内消息")
    void newApplyPushesInAppToEveryActiveOps() throws Exception {
        long activeOps = staffMapper.selectList(Wrappers.<ai.neargo.shop.platform.entity.SysOpsStaff>lambdaQuery()
                .eq(ai.neargo.shop.platform.entity.SysOpsStaff::getStatus, "ACTIVE")).size();
        assertThat(activeOps).as("种子里得有在用的运营账号，否则这条测的是空集").isGreaterThan(0);

        String user = TestLogin.consumer(mvc(), json, otpStore, "12600160001");
        String applyNo = submit(user, "意向通知测试店A", "13900000011", "CM-AL-A");

        List<MsgMessage> msgs = inboxOf(applyNo);
        assertThat(msgs).as("每个在用的运营各一条").hasSize((int) activeOps);
        assertThat(msgs.getFirst().getTitle()).isEqualTo("新的入驻意向");
        assertThat(msgs.getFirst().getBody()).as("正文要能看出是谁报的名").contains("意向通知测试店A");
        assertThat(msgs.getFirst().getReceiverType()).isEqualTo(MsgMessage.RECEIVER_OPS);
    }

    @Test
    @DisplayName("★★★ 没配企微 webhook 时，站内消息照发 —— 那一半才是生产常态")
    void inAppStillWorksWhenWebhookUnconfigured() throws Exception {
        assertThat(weComBotSender.available())
                .as("测试里本来就不该配 webhook；配了的话这条测的就不是「没配」那一半")
                .isFalse();

        String user = TestLogin.consumer(mvc(), json, otpStore, "12600160002");
        String applyNo = submit(user, "意向通知测试店B", "13900000012", "CM-AL-B");

        assertThat(inboxOf(applyNo)).as("企微没配，站内这条不能跟着丢").isNotEmpty();
    }

    @Test
    @DisplayName("★★ 同一份意向不会在同一个人那里堆两条 —— dedupKey 是单号")
    void sameApplyDoesNotStackTwice() throws Exception {
        String user = TestLogin.consumer(mvc(), json, otpStore, "12600160003");
        String applyNo = submit(user, "意向通知测试店C", "13900000013", "CM-AL-C");

        List<MsgMessage> msgs = inboxOf(applyNo);
        /*
         * **先验非空**：下面那句「收件人不重复」对空列表也成立（0 == 0）——
         * 做消融时这一条没跟着变红，正是因为少了这一句（[[falsifiable-verification-metric]]）。
         */
        assertThat(msgs).as("得先真的发出去，否则下面那句对空列表也成立").isNotEmpty();
        assertThat(msgs.stream().map(MsgMessage::getReceiverNo).distinct().count())
                .as("每个收件人只有一条").isEqualTo(msgs.size());
    }

    /** dedupKey 是 {@code APPLY_NEW:<单号>:<收件人>}，所以按前缀查 */
    private List<MsgMessage> inboxOf(String applyNo) {
        return messageMapper.selectList(Wrappers.<MsgMessage>lambdaQuery()
                .likeRight(MsgMessage::getDedupKey, "APPLY_NEW:" + applyNo + ":"));
    }

    private String submit(String token, String name, String phone, String communityNo) throws Exception {
        String body = mvc().perform(post("/mp/merchant/apply").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"contactPhone\":\"" + phone + "\","
                                + "\"category\":\"生鲜\",\"industry\":\"RETAIL\","
                                + "\"serviceScope\":\"COMMUNITY\",\"communityNos\":[\"" + communityNo + "\"]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("applyNo").asString();
    }
}
