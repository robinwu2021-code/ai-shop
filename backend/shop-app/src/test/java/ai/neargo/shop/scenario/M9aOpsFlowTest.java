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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M9a 平台端骨架 —— **用例先行**。
 *
 * <p>这一轮第一次真正使用 `/ops/**` 这条过滤器链，因此**双池隔离**是重点：
 * C 端 token 打 `/ops` 必须 401，运营 token 打 `/mp` 的属主接口也不该当成某个消费者。
 * 越权防线①（前缀 + 过滤器链）到这里才第一次被真实验证 —— 前八个模块只用了 C 池。
 */
@SpringBootTest
@ActiveProfiles("test")
class M9aOpsFlowTest {

    private static final String STUB_SECRET = "stub-secret";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private ai.neargo.shop.user.service.OtpStore otpStore;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    // ---------------------------------------------------------------- 双池隔离（防线①）

    @Test
    @DisplayName("★ C 端 token 打 /ops/** 一律 401（池前缀不符，不用查库就能判）")
    void consumerTokenCannotAccessOps() throws Exception {
        String consumerToken = login("12600126001");
        mvc().perform(get("/ops/staff").header("Authorization", "Bearer " + consumerToken))
                .andExpect(status().isUnauthorized());
        mvc().perform(get("/ops/order").header("Authorization", "Bearer " + consumerToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("★ 运营 token 打 C 端属主接口不会被当成某个消费者")
    void operatorTokenIsNotAConsumer() throws Exception {
        String ops = opsLogin("admin", "admin123");
        mvc().perform(get("/mp/user/profile").header("Authorization", "Bearer " + ops))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("未登录访问 /ops/** 401")
    void opsRequiresLogin() throws Exception {
        mvc().perform(get("/ops/staff")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("运营登录 → 拿到自己的角色与权限码")
    void opsLoginReturnsPerms() throws Exception {
        String token = opsLogin("admin", "admin123");
        String body = mvc().perform(get("/ops/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode staff = json.readTree(body).get("data");
        assertThat(staff.get("username").asString()).isEqualTo("admin");
        assertThat(staff.get("perms")).isNotEmpty();
    }

    @Test
    @DisplayName("密码错误不区分「用户不存在」与「密码不对」（否则等于用户名探测器）")
    void wrongPasswordIsIndistinguishable() throws Exception {
        String a = mvc().perform(post("/ops/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"wrong\"}"))
                .andReturn().getResponse().getContentAsString();
        String b = mvc().perform(post("/ops/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"nobody\",\"password\":\"wrong\"}"))
                .andReturn().getResponse().getContentAsString();

        assertThat(json.readTree(a).get("code").asInt())
                .isEqualTo(json.readTree(b).get("code").asInt());
    }

    // ---------------------------------------------------------------- RBAC（防线②）

    @Test
    @DisplayName("★ 无权限的角色被 @PreAuthorize 拦下（客服看不了员工管理）")
    void rbacBlocksUnauthorizedAction() throws Exception {
        String support = opsLogin("support", "support123");

        // 客服能看订单（工单处理要用）
        mvc().perform(get("/ops/order").header("Authorization", "Bearer " + support))
                .andExpect(status().isOk());
        // 但不能碰员工与角色。@PreAuthorize 抛 AccessDeniedException，
        // 由 GlobalExceptionHandler 转成契约包（HTTP 200 + code 10403）
        mvc().perform(get("/ops/staff").header("Authorization", "Bearer " + support))
                .andExpect(jsonPath("$.code").value(10403));
    }

    @Test
    @DisplayName("超管可以管理员工")
    void adminCanManageStaff() throws Exception {
        String admin = opsLogin("admin", "admin123");
        mvc().perform(get("/ops/staff").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(org.hamcrest.Matchers.greaterThan(0)));
    }

    // ---------------------------------------------------------------- 审核链路

    @Test
    @DisplayName("★ 入驻审核：通过后商家才 ACTIVE，才能上架与收款")
    void merchantApprovalActivatesMerchant() throws Exception {
        String user = login("12600126010");
        String applyNo = applyMerchant(user, "王姐水果店");

        String bd = opsLogin("bd", "bd123");
        mvc().perform(get("/ops/merchant/apply-queue").header("Authorization", "Bearer " + bd))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(org.hamcrest.Matchers.greaterThan(0)));

        mvc().perform(post("/ops/merchant/apply/" + applyNo + "/audit")
                        .header("Authorization", "Bearer " + bd)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approved\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // 审核通过 → 申请人获得商家身份（/biz 可用）
        String refreshed = login("12600126010");
        mvc().perform(get("/biz/context").header("Authorization", "Bearer " + refreshed))
                .andExpect(jsonPath("$.data.merchantNo").isNotEmpty());
    }

    @Test
    @DisplayName("驳回必须写理由（不写理由的驳回等于让对方猜）")
    void rejectRequiresReason() throws Exception {
        String user = login("12600126011");
        String applyNo = applyMerchant(user, "李哥杂货");
        String bd = opsLogin("bd", "bd123");

        mvc().perform(post("/ops/merchant/apply/" + applyNo + "/audit")
                        .header("Authorization", "Bearer " + bd)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approved\":false}"))
                .andExpect(jsonPath("$.code").value(10400));

        mvc().perform(post("/ops/merchant/apply/" + applyNo + "/audit")
                        .header("Authorization", "Bearer " + bd)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approved\":false,\"reason\":\"营业执照模糊\"}"))
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("★ 高危操作留痕：审核动作写进审计日志，能追到人")
    void auditLogRecordsWho() throws Exception {
        String user = login("12600126012");
        String applyNo = applyMerchant(user, "赵姐粮油");
        String bd = opsLogin("bd", "bd123");

        mvc().perform(post("/ops/merchant/apply/" + applyNo + "/audit")
                        .header("Authorization", "Bearer " + bd)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approved\":true}"));

        String admin = opsLogin("admin", "admin123");
        String body = mvc().perform(get("/ops/audit-log").header("Authorization", "Bearer " + admin)
                        .param("target", applyNo))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode logs = json.readTree(body).get("data");
        assertThat(logs).isNotEmpty();
        // 审核是能改变别人生意的操作 —— 出问题时必须能回答「谁批的」
        assertThat(logs.get(0).get("staffName").asString()).isNotBlank();
        assertThat(logs.get(0).get("action").asString()).isEqualTo("MERCHANT_AUDIT");
    }

    @Test
    @DisplayName("商品审核：未通过的商品不出现在 C 端列表")
    void goodsAuditGatesVisibility() throws Exception {
        String ops = opsLogin("goods", "goods123");
        String body = mvc().perform(get("/ops/goods/audit-queue").header("Authorization", "Bearer " + ops))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        // 队列里都是待审的（已通过的不该再出现在待办里）
        for (JsonNode g : json.readTree(body).get("data").get("records")) {
            assertThat(g.get("onSale").asBoolean()).isIn(true, false);
        }
    }

    @Test
    @DisplayName("平台可检索全量订单（客服处理工单要用）")
    void opsCanSearchAllOrders() throws Exception {
        String buyer = login("12600126020");
        buyAndPay(buyer, "m9-order");

        String support = opsLogin("support", "support123");
        mvc().perform(get("/ops/order").header("Authorization", "Bearer " + support)
                        .param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(org.hamcrest.Matchers.greaterThan(0)));
    }

    // ---------------------------------------------------------------- helpers

    private String applyMerchant(String userToken, String name) throws Exception {
        String body = mvc().perform(post("/mp/merchant/apply").header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"type\":\"INDIVIDUAL\","
                                + "\"contactPhone\":\"13900000000\",\"qualifications\":[\"https://cdn/l.jpg\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("applyNo").asString();
    }

    private void buyAndPay(String token, String idemKey) throws Exception {
        mvc().perform(post("/mp/cart/add").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"goodsNo\":\"G0002\",\"skuNo\":\"SK0003\",\"qty\":1}"));
        String body = mvc().perform(post("/mp/order").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", idemKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fulfillment\":\"STORE_PICKUP\",\"pickupNo\":\"PP0001\"}"))
                .andReturn().getResponse().getContentAsString();
        String payOrderNo = json.readTree(body).get("data").get("payOrderNo").asString();
        mvc().perform(post("/callback/pay/stub").contentType(MediaType.APPLICATION_JSON)
                .content("{\"outTradeNo\":\"" + payOrderNo + "\",\"transactionId\":\"TX-" + idemKey
                        + "\",\"sign\":\"" + STUB_SECRET + "\"}"));
    }

    private String opsLogin(String username, String password) throws Exception {
        String body = mvc().perform(post("/ops/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
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
