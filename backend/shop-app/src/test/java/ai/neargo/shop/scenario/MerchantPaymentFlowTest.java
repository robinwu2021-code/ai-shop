package ai.neargo.shop.scenario;

import ai.neargo.shop.support.TestLogin;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 收款进件：从「占位」推到「真的能收钱」。
 *
 * <p>这条链路此前<b>只有第一步</b>：主体激活时建一条 APPLYING 的记录，然后就没有然后了。
 * 整个系统能跑到下单，而收款方是个占位记录 —— 真钱进来分不出去，
 * 且这个状态在 B 端完全看不到。
 *
 * <p>用例覆盖四件事：状态可见、资料不齐要拦、开户成功要出收款号、驳回要给原因。
 */
@SpringBootTest
@ActiveProfiles("test")
class MerchantPaymentFlowTest {

    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;


    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                        .springSecurity())
                .build();
    }

    @Test
    @DisplayName("★ 激活后进件是占位态：店开着，但还收不了钱")
    void placeholderIsNotReceivable() throws Exception {
        String token = merchant("12600128001", "进件测试店A");

        mvc().perform(get("/biz/merchant/payment").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].applyStatus").value("APPLYING"))
                // **这一条是重点**：能开店 ≠ 能收钱。端上照着 canReceiveMoney 显示，
                // 而不是自己去比状态串 —— 比错的表现是"显示能收钱但收不了"
                .andExpect(jsonPath("$.data[0].canReceiveMoney").value(false))
                .andExpect(jsonPath("$.data[0].payMerchantNo").doesNotExist())
                // 缺什么要说清楚：「还差结算账户」比「审核中」有用得多
                .andExpect(jsonPath("$.data[0].missing[?(@=='settleAccount')]").exists())
                /*
                 * ★★ **占位态必须 submitted=false。**
                 *
                 * 入驻通过时建的占位与「已发给通道、在等回执」共用同一个 APPLYING，
                 * 端上只能照状态串显示，于是新商家看到的是
                 * 「审核中」+ 下面「还差结算账户」—— 他读成球在平台，于是坐等，
                 * 而球其实在他自己脚下。这一步正是「不能收钱」最常卡死的地方。
                 *
                 * 有了这个布尔，端上把它显示成「待补资料」（提醒色），
                 * 与「审核中」（安静的灰、他什么也做不了）分开。
                 */
                .andExpect(jsonPath("$.data[0].submitted").value(false));
    }

    @Test
    @DisplayName("★ 资料不齐不往通道发 —— 通道拒一次要等一个工作日")
    void incompleteSubmitIsRejectedLocally() throws Exception {
        String token = merchant("12600128002", "进件测试店B");

        mvc().perform(post("/biz/merchant/payment").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        // 个体户必须传执照，且没给结算账号
                        .content("{\"payChannel\":\"WECHAT\"}"))
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not(0)));
    }

    @Test
    @DisplayName("★ 进件通过：出 pay_merchant_no，且结算账号只回显掩码")
    void submitThenActive() throws Exception {
        String token = merchant("12600128003", "进件测试店C");

        String body = mvc().perform(post("/biz/merchant/payment")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payChannel\":\"WECHAT\",\"settleAccount\":\"6222021234567890123\","
                                + "\"licenses\":[\"https://cdn/license.jpg\"],"
                                + "\"contactName\":\"张三\",\"contactPhone\":\"13900000000\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.applyStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.data.canReceiveMoney").value(true))
                // 与占位态那条成对：发过通道的这一条 submitted 必须是 true，
                // 否则「待补资料 / 审核中」两个显示会调过来
                .andExpect(jsonPath("$.data.submitted").value(true))
                .andReturn().getResponse().getContentAsString();

        var data = json.readTree(body).get("data");
        // 开户成功才生成收款商户号业务键 —— 门店挂收款号引用的就是它
        assertThat(data.get("payMerchantNo").asString()).startsWith("PM");
        /*
         * **明文账号永不回显，包括给商家自己**（ADR-002 §5）。
         * B 端也可能被别人拿到，回显完整账号等于把它交出去。
         */
        assertThat(data.get("settleAccountMasked").asString()).isEqualTo("****0123");
        assertThat(body).doesNotContain("6222021234567890123");
    }

    @Test
    @DisplayName("★ 进件被驳回要带原因 —— 不给原因商家只能反复重提")
    void rejectedCarriesReason() throws Exception {
        // stub 网关按主体名判：带「驳回」二字就返回 REJECTED。
        // 假网关恒成功的话，驳回分支在开发期永远走不到，而它正是最容易写错的一段
        String token = merchant("12600128004", "驳回测试店");

        mvc().perform(post("/biz/merchant/payment").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payChannel\":\"WECHAT\",\"settleAccount\":\"6222029999888877776\","
                                + "\"licenses\":[\"https://cdn/license.jpg\"],"
                                + "\"contactName\":\"李四\",\"contactPhone\":\"13900000001\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.applyStatus").value("REJECTED"))
                .andExpect(jsonPath("$.data.canReceiveMoney").value(false))
                .andExpect(jsonPath("$.data.rejectReason").isNotEmpty());
    }

    @Test
    @DisplayName("开好的户不许重复提交 —— 通道会给新号，历史分账仍指向旧号")
    void activeCannotResubmit() throws Exception {
        String token = merchant("12600128005", "进件测试店D");
        String payload = "{\"payChannel\":\"WECHAT\",\"settleAccount\":\"6222021111222233334\","
                + "\"licenses\":[\"https://cdn/license.jpg\"],"
                + "\"contactName\":\"王五\",\"contactPhone\":\"13900000002\"}";

        mvc().perform(post("/biz/merchant/payment").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(jsonPath("$.data.applyStatus").value("ACTIVE"));

        mvc().perform(post("/biz/merchant/payment").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not(0)));
    }

    @Test
    @DisplayName("回查幂等：重复回执不会换掉已生成的收款商户号")
    void refreshKeepsPayMerchantNo() throws Exception {
        String token = merchant("12600128006", "进件测试店E");
        mvc().perform(post("/biz/merchant/payment").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"payChannel\":\"WECHAT\",\"settleAccount\":\"6222024444555566667\","
                        + "\"licenses\":[\"https://cdn/license.jpg\"],"
                        + "\"contactName\":\"赵六\",\"contactPhone\":\"13900000003\"}"));
        String first = payMerchantNo(token);

        // 通道重推回执是常态。每次换一个号的话，门店挂的收款号会指向一个不存在的行
        mvc().perform(post("/biz/merchant/payment/WECHAT/refresh")
                .header("Authorization", "Bearer " + token));
        assertThat(payMerchantNo(token)).isEqualTo(first);
    }

    // ------------------------------------------------------------------ 辅助

    private String payMerchantNo(String token) throws Exception {
        String body = mvc().perform(get("/biz/merchant/payment")
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get(0).get("payMerchantNo").asString();
    }

    @Test
    @DisplayName("★★ 一条进件记录都没有时：页面要有东西可填，提交要能建 —— 而不是「数据不存在」")
    void noRowYetStillUsable() throws Exception {
        String token = merchant("13500135070", "无进件记录店");
        String merchantNo = json.readTree(mvc().perform(get("/biz/merchant/profile")
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString())
                .get("data").get("merchantNo").asString();

        /*
         * 模拟「走别的路进来的主体」：迁移灌的种子、历史数据都没有这一行
         * （记录本该在入驻通过时由 ensurePayment 建）。
         *
         * 这条守的是一条**死路**：工作台上写着「收款进件没走完 · 去处理」，
         * 点进去整页只有一句话 —— 表单是 `v-if="current"`，而 current 来自这个接口。
         * 真实链路上撞见过：硬填了提交，也只得到一句「数据不存在」，
         * 而他填的东西没错、店也在，这句话没有任何可操作性。
         */
        jdbc.update("DELETE FROM mch_payment_merchant WHERE entity_no = ?", merchantNo);

        String body = mvc().perform(get("/biz/merchant/payment")
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].applyStatus").value("NONE"))
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(body).get("data").get(0).get("missing").size())
                .as("缺什么要说清楚，否则「未开始」等于没说")
                .isGreaterThan(0);

        // 提交要能把记录建出来，而不是 404
        mvc().perform(post("/biz/merchant/payment").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payChannel\":\"WECHAT\",\"settleAccountType\":\"PERSONAL_BANK_CARD\","
                                + "\"settleAccount\":\"6222021234567890123\",\"licenses\":[\"lic-1\"],"
                                + "\"contactName\":\"张老板\",\"contactPhone\":\"13500135070\"}"))
                .andExpect(jsonPath("$.code").value(0));

        mvc().perform(get("/biz/merchant/payment").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data[0].applyStatus")
                        .value(org.hamcrest.Matchers.not("NONE")));
    }

    private String merchant(String phone, String name) throws Exception {
        String user = login(phone);
        String body = mvc().perform(post("/mp/merchant/apply").header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"subject\":\"INDIVIDUAL_BIZ\","
                                + "\"contactName\":\"张三\",\"contactPhone\":\"13900000000\","
                                + "\"category\":\"食品\",\"serviceScope\":\"COMMUNITY\","
                                + "\"communityNos\":[\"CM001\"]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        String applyNo = json.readTree(body).get("data").get("applyNo").asString();

        String bd = opsLogin("bd", "bd123");
        mvc().perform(post("/ops/merchant/apply/" + applyNo + "/audit")
                        .header("Authorization", "Bearer " + bd)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"))
                .andExpect(jsonPath("$.code").value(0));
        // 商家身份是登录时解析进 BizContext 的，旧 token 上还没有
        // A7：/biz/** 只认 btk_，这里必须换 B 端令牌
        return TestLogin.merchantOwner(mvc(), json, otpStore, phone);
    }

    private String login(String phone) throws Exception {
        return TestLogin.consumer(mvc(), json, otpStore, phone);
    }

    private String opsLogin(String username, String password) throws Exception {
        String body = mvc().perform(post("/ops/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("token").asString();
    }
}
