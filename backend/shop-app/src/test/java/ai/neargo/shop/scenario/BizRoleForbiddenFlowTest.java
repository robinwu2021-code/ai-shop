package ai.neargo.shop.scenario;

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
 * B 端角色判权的**端到端**验证。
 *
 * <p>为什么必须有这个文件：{@code BizEndpointPermTest} 只证明「注解写上了」，
 * 而既有的 400 多条测试<b>全是老板账号跑的</b> —— 老板通配 {@code *}，
 * 所以注解就算完全不生效，那些测试也照样全绿。
 *
 * <p><b>「加了权限判断之后测试全过」本身就是可疑信号</b>：
 * 它可能意味着防住了，也可能意味着注解是装饰。只有用一个真的受限的账号打一次，
 * 才分得开这两种情况。
 */
@SpringBootTest
@ActiveProfiles("test")
class BizRoleForbiddenFlowTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    @DisplayName("★★★ 店员打不开结算页 —— 这是本次要修的那个越权口子")
    void clerkCannotSeeMoney() throws Exception {
        Staff s = staffWithRoles("12600250001", "结算越权店", "12600250002", "CLERK");

        // 矩阵 §2.2 写着店员「无财务、无结算账户可见性」，此前这条约束根本不存在
        forbidden(s, get("/biz/settle/bills"));
        forbidden(s, get("/biz/settle/rate-card"));
        forbidden(s, get("/biz/merchant/payment"));
        forbidden(s, get("/biz/points/account"));
    }

    @Test
    @DisplayName("★★ 注解真的在生效 —— 店员能做的事仍然能做")
    void clerkCanStillDoHisJob() throws Exception {
        Staff s = staffWithRoles("12600250010", "店员本职店", "12600250011", "CLERK");

        // 如果 @PreAuthorize 在 /biz 链路上根本不生效，上一条会失败；
        // 如果判断写反了（把店员该做的也挡了），这一条会失败。两条一起才说明它是对的
        allowed(s, get("/biz/pickup/orders"));
        allowed(s, get("/biz/order"));
        allowed(s, get("/biz/goods"));
    }

    @Test
    @DisplayName("★★ 店员改不了价，但改得了库存 —— 这条缝是权限边界")
    void clerkChangesStockButNotPrice() throws Exception {
        Staff s = staffWithRoles("12600250020", "改价越权店", "12600250021", "CLERK");

        forbidden(s, post("/biz/goods/save")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"偷偷改价\",\"type\":\"NORMAL\",\"skus\":[]}"));

        // 改库存是店员的高频日常，不该被挡 —— 它不出钱
        allowed(s, post("/biz/goods/NOT-EXIST/stock")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"skuNo\":\"X\",\"stock\":1}"));
    }

    @Test
    @DisplayName("★★ 店员管不了门店结构与员工 —— 那是提权路径")
    void clerkCannotTouchStructure() throws Exception {
        Staff s = staffWithRoles("12600250030", "结构越权店", "12600250031", "CLERK");

        forbidden(s, post("/biz/store/create")
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"我自己开的店\"}"));
        forbidden(s, get("/biz/staff"));
        forbidden(s, post("/biz/staff/X/store")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"storeNo\":\"X\",\"role\":\"MANAGER\"}"));
    }

    @Test
    @DisplayName("★★★ 多角色取并集 —— 店员+配送员两样都能干，但仍碰不到钱")
    void multiRoleUnionInRealRequest() throws Exception {
        Staff s = staffWithRoles("12600250040", "一人多岗店", "12600250041", "CLERK", "COURIER");

        allowed(s, get("/biz/pickup/orders"));   // 店员带来的
        allowed(s, get("/biz/order"));           // 两个角色都有
        // 并集不该凭空长出谁都没有的权限
        forbidden(s, get("/biz/settle/bills"));
        forbidden(s, post("/biz/goods/save")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"x\",\"type\":\"NORMAL\",\"skus\":[]}"));
    }

    @Test
    @DisplayName("★★ 理货员不能核销 —— 核销要面对顾客，他只对货")
    void pickerCannotVerify() throws Exception {
        Staff s = staffWithRoles("12600250050", "理货员店", "12600250051", "PICKER");

        allowed(s, get("/biz/pickup/picking"));
        forbidden(s, post("/biz/pickup/verify")
                .contentType(MediaType.APPLICATION_JSON).content("{\"verifyCode\":\"123456\"}"));
        forbidden(s, get("/biz/order"));   // 分拣单够他用，订单含金额
    }

    @Test
    @DisplayName("★★ 在这家店没有任何授权 = 零权限，不是「默认店员」")
    void noGrantInThisStoreMeansNothing() throws Exception {
        // 建了员工但**不给任何门店角色**
        Staff s = staffWithRoles("12600250060", "无授权店", "12600250061");

        forbidden(s, get("/biz/order"));
        forbidden(s, get("/biz/pickup/orders"));
        forbidden(s, get("/biz/settle/bills"));
    }

    @Test
    @DisplayName("★ 老板不受任何限制 —— 对照组，证明拒绝不是因为链路本身坏了")
    void ownerPassesEverything() throws Exception {
        String owner = merchant("12600250070", "老板对照店");
        mvc().perform(get("/biz/settle/bills").header("Authorization", "Bearer " + owner))
                .andExpect(jsonPath("$.code").value(0));
        mvc().perform(get("/biz/staff").header("Authorization", "Bearer " + owner))
                .andExpect(jsonPath("$.code").value(0));
    }

    // ---------------------------------------------------------------- 装配

    private record Staff(String token) {
    }

    private void forbidden(Staff s, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req)
            throws Exception {
        String body = mvc().perform(req.header("Authorization", "Bearer " + s.token()))
                .andReturn().getResponse().getContentAsString();
        int code = body.isBlank() ? 403 : json.readTree(body).get("code").asInt();
        // 70006 是**角色不够**的专用码；10403 是作用域拒绝（这家店没自提点之类），
        // 两者分开正是为了让这条断言能说清楚它在验什么
        assertThat(code).as("这个请求本该被角色挡住，实际返回 %s", body).isEqualTo(70006);
    }

    private void allowed(Staff s, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req)
            throws Exception {
        String body = mvc().perform(req.header("Authorization", "Bearer " + s.token()))
                .andReturn().getResponse().getContentAsString();
        int code = json.readTree(body).get("code").asInt();
        assertThat(code)
                .as("这个请求不该被**角色**挡住（业务错误、作用域拒绝都允许）：%s", body)
                .isNotEqualTo(70006);
    }

    /** 建一个员工，授予若干门店角色，返回他自己的登录态 */
    private Staff staffWithRoles(String ownerPhone, String shopName, String staffPhone,
                                 String... roles) throws Exception {
        String owner = merchant(ownerPhone, shopName);
        String store = json.readTree(mvc().perform(get("/biz/store/list")
                        .header("Authorization", "Bearer " + owner))
                .andReturn().getResponse().getContentAsString())
                .get("data").get(0).get("storeNo").asString();

        String staffNo = json.readTree(mvc().perform(post("/biz/staff")
                        .header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginPhone\":\"" + staffPhone + "\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString())
                .get("data").get("mchAccountNo").asString();

        for (String role : roles) {
            mvc().perform(post("/biz/staff/" + staffNo + "/store")
                            .header("Authorization", "Bearer " + owner)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"storeNo\":\"" + store + "\",\"role\":\"" + role + "\"}"))
                    .andExpect(jsonPath("$.code").value(0));
        }
        return new Staff(staffLogin(staffPhone));
    }

    /** 员工独立登录（App 路径）—— 与老板的 C 端账号登录是两条路 */
    private String staffLogin(String phone) throws Exception {
        mvc().perform(post("/biz/auth/otp/send").contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"" + phone + "\"}"));
        String code = otpStore.peek(phone).orElseThrow();
        String body = mvc().perform(post("/biz/auth/staff-login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + phone + "\",\"code\":\"" + code + "\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("token").asString();
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
        mvc().perform(post("/ops/merchant/apply/" + applyNo + "/audit")
                .header("Authorization", "Bearer " + opsLogin())
                .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"));
        return login(phone);
    }

    private String opsLogin() throws Exception {
        String body = mvc().perform(post("/ops/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("token").asString();
    }

    private String login(String phone) throws Exception {
        mvc().perform(post("/mp/user/otp/send").contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"" + phone + "\"}"));
        String code = otpStore.peek(phone).orElseThrow();
        String body = mvc().perform(post("/mp/user/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grantType\":\"PHONE_OTP\",\"principal\":\"" + phone
                                + "\",\"credential\":\"" + code + "\",\"agreed\":true}"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("token").asString();
    }
}
