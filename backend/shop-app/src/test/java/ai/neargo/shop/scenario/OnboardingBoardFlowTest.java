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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 运营端 · 进件看板（WS-C）+ 商家列表 CSV 状态筛（WS-A1）的真链路回归。
 *
 * <p>看板填的是一个真实盲区：入驻审核通过 = 能上架，收款进件通过 = 能收钱，两条链。
 * 审核过了但进件没走完的商家「货照上、单照来、钱收不到」，而这状态此前运营端
 * <b>没有一个跨商家的地方能看见</b>。这里验的是：激活派生出的 APPLYING 占位记录
 * 真的出现在看板上，且状态筛得动。
 */
@SpringBootTest
@ActiveProfiles("test")
class OnboardingBoardFlowTest {

    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;
    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ObjectMapper json;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    @DisplayName("★ 激活后进件占位（WECHAT/APPLYING）出现在进件看板，且按状态筛得动")
    void onboardingPlaceholderShowsOnBoard() throws Exception {
        String name = "进件看板测试店A";
        approveMerchant("12600127001", name, "CM-ONB-A");
        String admin = opsLogin("admin", "admin123");

        // 看板是分页包（裸数组会被前端当成空页）
        mvc().perform(get("/ops/onboarding").header("Authorization", "Bearer " + admin)
                        .param("keyword", name))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records").exists())
                .andExpect(jsonPath("$.data.total").exists())
                // 激活派生的占位：微信通道、审核中、还收不了钱
                .andExpect(jsonPath("$.data.records[?(@.merchantName=='" + name + "')].payChannel")
                        .value(org.hamcrest.Matchers.contains("WECHAT")))
                .andExpect(jsonPath("$.data.records[?(@.merchantName=='" + name + "')].applyStatus")
                        .value(org.hamcrest.Matchers.contains("APPLYING")))
                .andExpect(jsonPath("$.data.records[?(@.merchantName=='" + name + "')].canReceiveMoney")
                        .value(org.hamcrest.Matchers.contains(false)));

        // 只看 APPLYING：占位在
        mvc().perform(get("/ops/onboarding").header("Authorization", "Bearer " + admin)
                        .param("keyword", name).param("status", "APPLYING"))
                .andExpect(jsonPath("$.data.records[?(@.merchantName=='" + name + "')]").exists());
        // 只看 ACTIVE：占位不在（它还没进件成功）
        mvc().perform(get("/ops/onboarding").header("Authorization", "Bearer " + admin)
                        .param("keyword", name).param("status", "ACTIVE"))
                .andExpect(jsonPath("$.data.records[?(@.merchantName=='" + name + "')]").doesNotExist());
    }

    @Test
    @DisplayName("★ 进件看板要 admission 权限：客服（无）被拦，超管（有）放行")
    void onboardingBoardIsPermGated() throws Exception {
        // @PreAuthorize 抛 AccessDeniedException → 契约包 HTTP 200 + code 10403
        String support = opsLogin("support", "support123");
        mvc().perform(get("/ops/onboarding").header("Authorization", "Bearer " + support))
                .andExpect(jsonPath("$.code").value(10403));

        String admin = opsLogin("admin", "admin123");
        mvc().perform(get("/ops/onboarding").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("★ 商家列表 status 支持逗号分隔多态 —— 单值 eq 会把 CSV 静默匹配成零行")
    void merchantListAcceptsCsvStatus() throws Exception {
        String name = "CSV状态筛测试店";
        approveMerchant("12600127002", name, "CM-ONB-CSV");
        String admin = opsLogin("admin", "admin123");

        // 修复前：status=ACTIVE,SUSPENDED 会 eq("ACTIVE,SUSPENDED") → 空列表，运营以为没商家
        mvc().perform(get("/ops/merchants").header("Authorization", "Bearer " + admin)
                        .param("status", "ACTIVE,SUSPENDED").param("keyword", name))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[?(@.name=='" + name + "')]").exists());

        // 单值仍照常工作
        mvc().perform(get("/ops/merchants").header("Authorization", "Bearer " + admin)
                        .param("status", "ACTIVE").param("keyword", name))
                .andExpect(jsonPath("$.data.records[?(@.name=='" + name + "')]").exists());
    }

    // ---------------------------------------------------------------- helpers

    /** 走完「C 端提交 → 平台通过」，激活派生出 ACTIVE 主体 + APPLYING 进件占位。 */
    private void approveMerchant(String phone, String name, String communityNo) throws Exception {
        String user = TestLogin.consumer(mvc(), json, otpStore, phone);
        String body = mvc().perform(post("/mp/merchant/apply").header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"subject\":\"INDIVIDUAL_BIZ\","
                                + "\"contactName\":\"张三\",\"contactPhone\":\"13900000000\","
                                + "\"category\":\"生鲜\",\"desc\":\"社区生鲜店\","
                                + "\"serviceScope\":\"COMMUNITY\",\"communityNos\":[\"" + communityNo + "\"],"
                                + "\"licenses\":[\"https://cdn/l.jpg\"]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        String applyNo = json.readTree(body).get("data").get("applyNo").asString();

        String bd = opsLogin("bd", "bd123");
        mvc().perform(post("/ops/merchant/apply/" + applyNo + "/audit")
                        .header("Authorization", "Bearer " + bd)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"))
                .andExpect(jsonPath("$.code").value(0));
    }

    private String opsLogin(String username, String password) throws Exception {
        String body = mvc().perform(post("/ops/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("token").asString();
    }
}
