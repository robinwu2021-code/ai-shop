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

/**
 * 入驻意向的「提交后可查看、待审核可改、审核中锁定」（TDD-入驻意向 §2）。
 *
 * <p><b>这一组盯的是「改了之后库里是不是真的变了」</b>，不是「接口返回了 code=0」——
 * 逐字段 set 写成 updateById 的话，清空类的修改根本不会生成 set 语句，
 * 而接口照样成功、VO 照样是新值（[[mybatis-plus-skips-nulls]]）。
 */
@SpringBootTest
@ActiveProfiles("test")
class MerchantApplyEditFlowTest {

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
    @DisplayName("★★★ 待审核时改意向：改完从库里读回来是新值，不是「接口说成功了」")
    void pendingApplyCanBeEditedAndIsPersisted() throws Exception {
        String user = TestLogin.consumer(mvc(), json, otpStore, "12600150001");
        String applyNo = submit(user, "意向测试店A", "13900000001", "生鲜", "CM-AE-A");

        edit(user, applyNo, "{\"name\":\"意向测试店A-改过\",\"contactPhone\":\"13900000002\","
                + "\"category\":\"粮油\",\"industry\":\"RETAIL\"}")
                .andExpect(jsonPath("$.code").value(0));

        JsonNode mine = mine(user);
        assertThat(mine.get("name").asString()).isEqualTo("意向测试店A-改过");
        assertThat(mine.get("contactPhone").asString()).isEqualTo("13900000002");
        assertThat(mine.get("category").asString()).isEqualTo("粮油");
        assertThat(mine.get("status").asString()).as("改完还是待审核，不该被重置成别的状态")
                .isEqualTo("PENDING");
    }

    @Test
    @DisplayName("★★★ 清空一个格子要真的清空 —— updateById 会跳过 null，那句 set 根本不生成")
    void clearingAFieldActuallyClearsIt() throws Exception {
        String user = TestLogin.consumer(mvc(), json, otpStore, "12600150002");
        String applyNo = submit(user, "意向测试店B", "13900000003", "生鲜", "CM-AE-B");

        // 先填上推荐人与简介，再把它们清掉 —— 改意向最常见的动作就是「删掉一个填错的格子」
        edit(user, applyNo, "{\"name\":\"意向测试店B\",\"contactPhone\":\"13900000003\","
                + "\"category\":\"生鲜\",\"referrerPhone\":\"13700000001\",\"desc\":\"临时写的简介\"}");
        assertThat(mine(user).get("referrerPhone").asString()).isEqualTo("13700000001");

        edit(user, applyNo, "{\"name\":\"意向测试店B\",\"contactPhone\":\"13900000003\","
                + "\"category\":\"生鲜\"}");

        JsonNode after = mine(user);
        assertThat(after.get("referrerPhone").isNull()).as("推荐人要真的没了").isTrue();
        assertThat(after.get("desc") == null || after.get("desc").isNull()
                || after.get("desc").asString().isEmpty()).as("简介同理").isTrue();
    }

    @Test
    @DisplayName("★★★ 运营开始看了就锁 —— 否则他看的与库里存的不是同一份")
    void reviewingApplyIsLocked() throws Exception {
        String user = TestLogin.consumer(mvc(), json, otpStore, "12600150003");
        String applyNo = submit(user, "意向测试店C", "13900000004", "生鲜", "CM-AE-C");

        String bd = opsLogin("bd", "bd123");
        // 「受理」= 告诉商家有人在看了，它把单推到 REVIEWING（OpsPlatformController#acceptApply）
        mvc().perform(post("/ops/merchant/apply/" + applyNo + "/accept")
                        .header("Authorization", "Bearer " + bd))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());

        edit(user, applyNo, "{\"name\":\"改不动的\",\"contactPhone\":\"13900000004\",\"category\":\"生鲜\"}")
                .andExpect(jsonPath("$.code").value(10467));

        assertThat(mine(user).get("name").asString()).as("库里还是原来那个名字")
                .isEqualTo("意向测试店C");
    }

    @Test
    @DisplayName("★★★ 不是自己的单当「不存在」，不是「无权限」—— 后者等于确认单号有效")
    void othersApplyLooksLikeNotFound() throws Exception {
        String owner = TestLogin.consumer(mvc(), json, otpStore, "12600150004");
        String applyNo = submit(owner, "意向测试店D", "13900000005", "生鲜", "CM-AE-D");

        String stranger = TestLogin.consumer(mvc(), json, otpStore, "12600150005");
        edit(stranger, applyNo, "{\"name\":\"别人的单\",\"contactPhone\":\"13900000006\",\"category\":\"生鲜\"}")
                .andExpect(jsonPath("$.code").value(10404));
    }

    @Test
    @DisplayName("★★ 冷启动配置发商家版 App 的下载地址 —— 端上不自己拼域名")
    void bootstrapCarriesMerchantAppLinks() throws Exception {
        String body = mvc().perform(get("/mp/config/bootstrap"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        JsonNode app = json.readTree(body).get("data").get("merchantApp");
        assertThat(app).as("这一段要在，哪怕两档都是空的 —— 端上按空判断显不显示").isNotNull();
        assertThat(app.has("android")).isTrue();
        assertThat(app.has("ios")).isTrue();
    }

    private org.springframework.test.web.servlet.ResultActions edit(String token, String applyNo, String bodyJson)
            throws Exception {
        return mvc().perform(post("/mp/merchant/apply/" + applyNo)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(bodyJson));
    }

    private JsonNode mine(String token) throws Exception {
        String body = mvc().perform(get("/mp/merchant/apply").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data");
    }

    private String submit(String token, String name, String phone, String category, String communityNo)
            throws Exception {
        String body = mvc().perform(post("/mp/merchant/apply").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"contactPhone\":\"" + phone + "\","
                                + "\"category\":\"" + category + "\",\"industry\":\"RETAIL\","
                                + "\"serviceScope\":\"COMMUNITY\",\"communityNos\":[\"" + communityNo + "\"]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("applyNo").asString();
    }

    private String opsLogin(String username, String password) throws Exception {
        String body = mvc().perform(post("/ops/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("token").asString();
    }
}
