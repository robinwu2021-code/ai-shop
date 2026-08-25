package ai.neargo.shop.scenario;

import ai.neargo.shop.support.TestLogin;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 没有任何支付通道实现时，进件要说人话。
 *
 * <p><b>这不是假想的处境，这就是今天的生产</b>：{@code PayApplymentGateway} 只有一个实现
 * （{@code StubApplymentGateway}），而它挂在 {@code shop.pay.stub} 上、生产默认关
 * （「假装支付成功」是资金事故）。于是生产上那张 map 是空的，
 * 商家点「提交进件」必然失败 —— 问题只在于失败时说什么。
 *
 * <p>原先说的是 {@code BAD_REQUEST}「请求参数有误」。商家把结算账号、执照照片、
 * 联系人一整张表填完，得到这么一句，只能回去反复改那几个字段 ——
 * <b>而无论怎么改都一样被拒</b>。这与门店额度那条踩过的坑是同一类
 * （见 {@code StoreAndStaffFlowTest.quotaIsEnforced} 的注释）：
 * 把「你改不了的事」说成「你填错了」，人就会一直试。
 *
 * <p>所以这个类**刻意把 stub 关掉**（{@code properties}），复现生产装配。
 * 别的用例跑在 {@code testcfg} 的 {@code stub: true} 下，那是在验通了之后的链路。
 */
@SpringBootTest(properties = {
        // 关掉假网关 = 复现生产装配（唯一实现挂在这个开关上，生产默认关）
        "shop.pay.stub=false",
        /*
         * **必须另开一个库**。改了 properties 就是另一个 Spring 上下文，
         * 而上下文初始化会把 schema-test.sql 再跑一遍 —— 跑在同一个
         * `jdbc:h2:mem:shop` 上就是往已经有数据的表里重插种子，
         * 整个上下文起不来（sys_industry 主键冲突），症状与本用例毫无关系。
         * 与 JobRunRecordFlowTest 同一手法。
         */
        "spring.datasource.url=jdbc:h2:mem:pay-nochannel;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
})
@ActiveProfiles("test")
class PayChannelUnavailableFlowTest {

    /** 与 {@code ErrorCode.PAY_CHANNEL_UNAVAILABLE} 同值。写死是为了改码时这里也红 */
    private static final int PAY_CHANNEL_UNAVAILABLE = 70045;
    /** 「请求参数有误」。这一条要断言的正是**不能**是它 */
    private static final int BAD_REQUEST = 10400;

    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                        .springSecurity())
                .build();
    }

    @Test
    @DisplayName("★★ 通道没接通时提交进件：说「通道没接通」，不能说「请求参数有误」")
    void submitSaysChannelNotConnected() throws Exception {
        String token = merchant("12600131001", "通道未接通·提交");

        /*
         * 资料**填全了** —— 这一点是这条用例的关键。
         * 少填一项的话，被拦在本地校验那一步（资料不齐），走不到取网关，
         * 于是这条用例会变成绿的而什么都没验到。
         */
        String body = mvc().perform(post("/biz/merchant/payment")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payChannel\":\"WECHAT\",\"settleAccount\":\"6222021234567890123\","
                                + "\"licenses\":[\"https://cdn/license.jpg\"],"
                                + "\"contactName\":\"张三\",\"contactPhone\":\"13900000000\"}"))
                .andReturn().getResponse().getContentAsString();

        int code = json.readTree(body).get("code").asInt();
        assertThat(code)
                .as("通道没接通不是「参数有误」—— 说成参数有误，他会回去反复改那几个字段")
                .isNotEqualTo(BAD_REQUEST);
        assertThat(code).isEqualTo(PAY_CHANNEL_UNAVAILABLE);

        String msg = json.readTree(body).get("msg").asString();
        assertThat(msg)
                .as("话要说到「不是你的资料有问题」，否则他还是会去改资料")
                .isNotBlank();
        assertThat(msg).doesNotContain("参数");

        /*
         * ★ **明文结算账号不能因为失败就漏出去**。
         * 失败路径最容易漏：成功路径有人盯着掩码，失败路径常常把整个请求体回显进报错。
         */
        assertThat(body).doesNotContain("6222021234567890123");
    }

    @Test
    @DisplayName("★ 通道没接通不影响「收款设置」页打开 —— 他至少要看得见自己卡在哪")
    void statusPageStillOpens() throws Exception {
        String token = merchant("12600131002", "通道未接通·查看");

        mvc().perform(get("/biz/merchant/payment").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(0));
    }

    // ------------------------------------------------------------ 脚手架

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

        String bd = opsLogin();
        mvc().perform(post("/ops/merchant/apply/" + applyNo + "/audit")
                        .header("Authorization", "Bearer " + bd)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"))
                .andExpect(jsonPath("$.code").value(0));
        return login(phone);
    }

    private String login(String phone) throws Exception {
        return TestLogin.consumer(mvc(), json, otpStore, phone);
    }

    private String opsLogin() throws Exception {
        String body = mvc().perform(post("/ops/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"bd\",\"password\":\"bd123\"}"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("token").asString();
    }
}
