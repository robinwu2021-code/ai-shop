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
 * 店铺码档案与印刷量登记（TDD-门店获客埋点与看板 §五 闸门 V5）。
 *
 * <p>这一页最容易出的问题是**把「还没人登记」显示成「印了 0 张」** ——
 * 两者在界面上长得一样，而运营据此判断该去催谁登记。
 */
@SpringBootTest
@ActiveProfiles("test")
class StoreQrcodeFlowTest {

    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;
    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private ai.neargo.shop.merchant.service.StoreCodeService storeCodeService;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    /** <b>V5</b>：没登记过印刷量 → {@code printed} 是 null，不是 0。 */
    @Test
    @DisplayName("★ 没登记过印刷量的店 printed 给 null —— 与「印了 0 张」不是一回事")
    void unregisteredPrintedIsNullNotZero() throws Exception {
        String merchantNo = approvedMerchantNo("12600130001", "店铺码测试店A", "CM-QR-A");
        storeCodeService.ensureFor(merchantNo);

        String admin = opsLogin("admin", "admin123");
        JsonNode row = qrcodeRowOf(admin, merchantNo);
        assertThat(row).as("生成过码的店没有出现在店铺码列表里").isNotNull();
        // ★ 关键：null 而不是 0
        assertThat(row.get("printed").isNull())
                .as("从没登记过印刷量却显示成 0 —— 运营会以为已经登记过、印了零张")
                .isTrue();
        // 扫码次数相反：埋点一直在记，0 就是真的没人扫
        assertThat(row.get("scanCount").asLong()).isEqualTo(0);
    }

    @Test
    @DisplayName("★ 登记两次印刷 → printed 累加；冲减补负数行而不是改历史")
    void printedAccumulatesAndSupportsNegative() throws Exception {
        String merchantNo = approvedMerchantNo("12600130002", "店铺码测试店B", "CM-QR-B");
        storeCodeService.ensureFor(merchantNo);
        String admin = opsLogin("admin", "admin123");

        recordPrint(admin, merchantNo, 200, "10x10cm");
        recordPrint(admin, merchantNo, 300, "6x6cm");
        assertThat(qrcodeRowOf(admin, merchantNo).get("printed").asInt()).isEqualTo(500);
        // 尺寸取最近一次那批 —— 尺寸属于那次印刷，不是门店的固有属性
        assertThat(qrcodeRowOf(admin, merchantNo).get("size").asString()).isEqualTo("6x6cm");

        // 印多了冲减：补一行负数，历史行不动
        recordPrint(admin, merchantNo, -100, null);
        assertThat(qrcodeRowOf(admin, merchantNo).get("printed").asInt()).isEqualTo(400);
    }

    @Test
    @DisplayName("登记 0 张被拒 —— 它既不是印了也不是冲减，留一行只是噪声")
    void zeroQtyRejected() throws Exception {
        String merchantNo = approvedMerchantNo("12600130003", "店铺码测试店C", "CM-QR-C");
        String admin = opsLogin("admin", "admin123");
        mvc().perform(post("/ops/stores/" + merchantNo + "/qrcode/print")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"qty\":0}"))
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not(0)));
    }

    @Test
    @DisplayName("★ 扫过码之后 scanCount 是真的（埋点与店铺码页读的是同一份数据）")
    void scanCountComesFromRealVisits() throws Exception {
        String merchantNo = approvedMerchantNo("12600130004", "店铺码测试店D", "CM-QR-D");
        String code = storeCodeService.ensureFor(merchantNo);

        mvc().perform(get("/mp/store/by-code").param("storeCode", code)
                .param("deviceId", "DEV-QR-D")).andExpect(status().isOk());
        mvc().perform(get("/mp/store/by-code").param("storeCode", code)
                .param("deviceId", "DEV-QR-D")).andExpect(status().isOk());

        String admin = opsLogin("admin", "admin123");
        assertThat(qrcodeRowOf(admin, merchantNo).get("scanCount").asLong())
                .as("店铺码页的扫码数与埋点对不上 —— 两处读的不是同一份数据")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("★ 店铺码要 store:page:audit：客服（无）被拦")
    void qrcodesArePermGated() throws Exception {
        String support = opsLogin("support", "support123");
        mvc().perform(get("/ops/stores/qrcodes").header("Authorization", "Bearer " + support))
                .andExpect(jsonPath("$.code").value(10403));
    }

    // ---------------------------------------------------------------- helpers

    private void recordPrint(String opsToken, String merchantNo, int qty, String size) throws Exception {
        String body = size == null
                ? "{\"qty\":" + qty + "}"
                : "{\"qty\":" + qty + ",\"size\":\"" + size + "\"}";
        mvc().perform(post("/ops/stores/" + merchantNo + "/qrcode/print")
                        .header("Authorization", "Bearer " + opsToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.code").value(0));
    }

    private JsonNode qrcodeRowOf(String opsToken, String merchantNo) throws Exception {
        String body = mvc().perform(get("/ops/stores/qrcodes")
                        .header("Authorization", "Bearer " + opsToken)
                        .param("keyword", merchantNo).param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        for (JsonNode r : json.readTree(body).get("data").get("records")) {
            if (merchantNo.equals(r.get("merchantNo").asString())) {
                return r;
            }
        }
        return null;
    }

    private String approvedMerchantNo(String phone, String name, String communityNo) throws Exception {
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

        String mine = mvc().perform(get("/mp/merchant/apply").header("Authorization", "Bearer " + user))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(mine).get("data").get("merchantNo").asString();
    }

    private String opsLogin(String username, String password) throws Exception {
        String body = mvc().perform(post("/ops/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("token").asString();
    }
}
