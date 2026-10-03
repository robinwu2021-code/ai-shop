package ai.neargo.shop.scenario;

import ai.neargo.shop.message.MessageService;
import ai.neargo.shop.message.entity.MsgMessage;
import ai.neargo.shop.message.entity.MsgTemplate;
import ai.neargo.shop.message.mapper.MessageMappers.MessageMapper;
import ai.neargo.shop.message.mapper.MessageMappers.TemplateMapper;
import ai.neargo.shop.platform.entity.SysOpsStaff;
import ai.neargo.shop.platform.mapper.PlatformMappers.StaffMapper;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 平台端通知（顶栏铃铛）与营销频控（TDD-通知与消息推送 §二期）。
 *
 * <p>受众按**权限码**解析（谁能处理工单谁收到），不是按角色名 ——
 * 角色随时会被运营改组，权限码才是「谁该被叫来干活」的稳定判据。
 */
@SpringBootTest
@ActiveProfiles("test")
class OpsNotifyFlowTest {

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;
    @Autowired
    private StaffMapper staffMapper;
    @Autowired
    private MessageMapper messageMapper;
    @Autowired
    private TemplateMapper templateMapper;
    @Autowired
    private MessageService messageService;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup
                        .SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    @DisplayName("★ 用户提工单 → 有处理权限的运营收到「新工单」，读后角标回落")
    void ticketNotifiesHandlers() throws Exception {
        String user = ai.neargo.shop.support.TestLogin.consumer(mvc(), json, otpStore, "12700127301");
        String admin = ai.neargo.shop.support.TestLogin.admin(mvc(), json);

        mvc().perform(post("/mp/ticket").header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject\":\"取货码扫不出来\",\"content\":\"自提点扫码失败\"}"))
                .andExpect(status().isOk());

        JsonNode msg = find(opsMessages(admin), "新工单");
        assertThat(msg).isNotNull();
        assertThat(msg.get("body").asString()).contains("取货码扫不出来");
        assertThat(msg.get("link").asString()).contains("/messages?tab=tickets");

        long unread = unreadCount(admin);
        assertThat(unread).isPositive();

        mvc().perform(post("/ops/message/" + msg.get("messageNo").asString() + "/read")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk());
        assertThat(unreadCount(admin)).isEqualTo(unread - 1);
    }

    @Test
    @DisplayName("没有工单处理权限的运营（BD）收不到 —— 受众按权限码解析")
    void staffWithoutPermIsNotNotified() throws Exception {
        // 直接落一个 BD 账号：BD 的角色映射里没有 message:ticket:handle
        String staffNo = "ST-BD-TEST";
        if (staffMapper.selectCount(Wrappers.<SysOpsStaff>lambdaQuery()
                .eq(SysOpsStaff::getStaffNo, staffNo)) == 0) {
            SysOpsStaff s = new SysOpsStaff();
            s.setStaffNo(staffNo);
            s.setUsername("bd-test");
            s.setRealName("测试BD");
            s.setPassword("x");
            s.setRoles("[\"BD\"]");
            s.setStatus("ACTIVE");
            staffMapper.insert(s);
        }

        String user = ai.neargo.shop.support.TestLogin.consumer(mvc(), json, otpStore, "12700127302");
        mvc().perform(post("/mp/ticket").header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject\":\"运费问题\",\"content\":\"多收了运费\"}"))
                .andExpect(status().isOk());

        // BD 的 OPS 收件箱必须是空的（按 receiver 直查库，不用登录他）
        long got = messageMapper.selectCount(Wrappers.<MsgMessage>lambdaQuery()
                .eq(MsgMessage::getReceiverType, MsgMessage.RECEIVER_OPS)
                .eq(MsgMessage::getReceiverNo, staffNo));
        assertThat(got).isZero();
    }

    @Test
    @DisplayName("★ 营销频控：同模板未过最小间隔第二条被拦；停用模板一条都发不出")
    void marketingQuotaIsEnforced() throws Exception {
        String user = ai.neargo.shop.support.TestLogin.consumer(mvc(), json, otpStore, "12700127303");
        String userNo = profileUserNo(user);

        MsgTemplate tpl = new MsgTemplate();
        tpl.setTemplateNo("TPL-QUOTA-T1");
        tpl.setName("周末大促");
        tpl.setChannel("INAPP");
        tpl.setContent("全场八折");
        tpl.setEnabled(true);
        if (templateMapper.selectCount(Wrappers.<MsgTemplate>lambdaQuery()
                .eq(MsgTemplate::getTemplateNo, tpl.getTemplateNo())) == 0) {
            templateMapper.insert(tpl);
        }

        assertThat(messageService.pushMarketing(userNo, "TPL-QUOTA-T1",
                "周末大促", "全场八折", null, "mkt-q-1")).isTrue();
        // 同模板最小间隔（默认 24h）内的第二条：拦
        assertThat(messageService.pushMarketing(userNo, "TPL-QUOTA-T1",
                "周末大促", "全场八折", null, "mkt-q-2")).isFalse();

        // 交易消息不受频控影响 —— 到货通知被拦是事故
        long marketing = messageMapper.selectCount(Wrappers.<MsgMessage>lambdaQuery()
                .eq(MsgMessage::getReceiverNo, userNo)
                .eq(MsgMessage::getMsgType, MsgMessage.MARKETING));
        assertThat(marketing).isEqualTo(1);

        // 停用模板即刻生效
        MsgTemplate saved = templateMapper.selectOne(Wrappers.<MsgTemplate>lambdaQuery()
                .eq(MsgTemplate::getTemplateNo, "TPL-QUOTA-T1").last("limit 1"));
        saved.setEnabled(false);
        templateMapper.updateById(saved);
        assertThat(messageService.pushMarketing(userNo, "TPL-QUOTA-T1",
                "周末大促", "全场八折", null, "mkt-q-3")).isFalse();
    }

    // ---------------------------------------------------------------- helpers

    private JsonNode opsMessages(String token) throws Exception {
        String body = mvc().perform(get("/ops/message").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data");
    }

    private long unreadCount(String token) throws Exception {
        String body = mvc().perform(get("/ops/message/unread-count")
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").asLong();
    }

    private String profileUserNo(String token) throws Exception {
        String body = mvc().perform(get("/mp/user/profile").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("userNo").asString();
    }

    private JsonNode find(JsonNode messages, String titlePart) {
        for (JsonNode m : messages) {
            if (m.get("title").asString().contains(titlePart)) {
                return m;
            }
        }
        return null;
    }
}
