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

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 入驻意向的<b>行业口径</b>（TDD-C端入驻意向-行业口径）。
 *
 * <p>盯的是「两个列表量的不是同一件事」：{@code industries} 回答「平台能不能接这类商家」，
 * {@code intentIndustries} 回答「商家能不能表达想做这一类」。一期只开了两档零售与生活服务，
 * 于是按前一把尺渲染的意向屏上，想开餐饮的人只能选「线下零售」——
 * 那条信息在入库的一刻就丢了，而意向表的价值恰恰在于收集平台还接不了的那些。
 */
@SpringBootTest
@ActiveProfiles("test")
class MerchantApplyIntentIndustryFlowTest {

    /*
     * 手机号段 **126001630xx**：一人同时只能有一份进行中的入驻申请
     * （`uk_apply_active_owner`），所以号段撞了别的类就是 10409 CONFLICT。
     * 最初用的 126001600xx 与 `QuickStartFlowTest` 一字不差 —— 它的「快速开店」
     * 会给那个人建一张待补证照的占位单，把 active_owner 占住。
     * 症状是**单独跑四条全绿、全量跑三条红在「提交入驻」那一步**，
     * 而报错（CONFLICT）与「行业口径」这件事毫不相干。加号段前先
     * `grep -rn <前八位> backend/shop-app/src/test/java`。
     */

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
    @DisplayName("★★★ 意向口径盖住未开放的行业，且进件口径是它的真子集")
    void intentIndustriesCoversDisabledOnes() throws Exception {
        JsonNode master = master();
        List<String> intent = codes(master.get("intentIndustries"));
        List<String> admit = codes(master.get("industries"));

        /*
         * **两个数都要非零**：断言「意向 ⊇ 进件」对两个空集同样成立 ——
         * 那是最容易通过的一种假绿（[[falsifiable-verification-metric]]）。
         */
        assertThat(admit).as("进件口径不该是空的，否则下面的包含关系不说明任何事").isNotEmpty();
        assertThat(intent).as("意向口径不该是空的").isNotEmpty();
        assertThat(intent).as("意向口径要盖住进件口径").containsAll(admit);
        assertThat(intent.size())
                .as("意向口径要**严格更宽** —— 一样宽就说明还在按 enabled 过滤")
                .isGreaterThan(admit.size());

        // 一期停用的那几档必须在意向里出现，并且标着「还没开放」
        for (String code : List.of("CATERING", "ENTERTAINMENT", "ONLINE", "OTHER")) {
            JsonNode row = row(master.get("intentIndustries"), code);
            assertThat(row).as(code + " 要出现在意向口径里").isNotNull();
            assertThat(row.get("open").asBoolean()).as(code + " 一期未开放，要标成 open=false").isFalse();
            assertThat(row.get("name").asString()).as("要带展示名，端上不自己翻码").isNotEmpty();
        }
        // 已开放的那一档同样要标对，否则 open 这一位是个常量
        JsonNode retail = row(master.get("intentIndustries"), "RETAIL");
        assertThat(retail).isNotNull();
        assertThat(retail.get("open").asBoolean()).as("RETAIL 一期开着，要标成 open=true").isTrue();
    }

    @Test
    @DisplayName("★★★ 选「其他」时手填的行业要落库，并且运营看得到")
    void industryNoteIsPersistedAndReachesOps() throws Exception {
        String user = TestLogin.consumer(mvc(), json, otpStore, "12600163001");
        submit(user, "意向行业店A", "13900010001", "OTHER", "宠物洗护");

        JsonNode mine = mine(user);
        assertThat(mine.get("industry").asString()).isEqualTo("OTHER");
        assertThat(mine.get("industryNote").asString())
                .as("手填的那句话要在 —— 收了不展示等于没收").isEqualTo("宠物洗护");
    }

    @Test
    @DisplayName("★★★ 改成具体行业时手填的那句话要清掉 —— 留着就是两个对不上的答案")
    void industryNoteClearedWhenIndustryIsNotOther() throws Exception {
        String user = TestLogin.consumer(mvc(), json, otpStore, "12600163002");
        String applyNo = submit(user, "意向行业店B", "13900010002", "OTHER", "汽车美容");
        assertThat(mine(user).get("industryNote").asString()).isEqualTo("汽车美容");

        // 改成零售，手填那一格照旧发上去 —— 后端要自己收口，不能指望端上记得清
        mvc().perform(post("/mp/merchant/apply/" + applyNo)
                        .header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"意向行业店B\",\"contactPhone\":\"13900010002\","
                                + "\"category\":\"生鲜\",\"industry\":\"RETAIL\","
                                + "\"industryNote\":\"汽车美容\"}"))
                .andExpect(jsonPath("$.code").value(0));

        JsonNode after = mine(user);
        assertThat(after.get("industry").asString()).isEqualTo("RETAIL");
        assertThat(after.get("industryNote") == null || after.get("industryNote").isNull())
                .as("选了具体行业，手填那句话必须没了").isTrue();
    }

    @Test
    @DisplayName("★★ 未开放的行业照样收 —— 拦下来就等于又拿准入的尺子量意向")
    void disabledIndustryIsStillAccepted() throws Exception {
        String user = TestLogin.consumer(mvc(), json, otpStore, "12600163003");
        submit(user, "意向行业店C", "13900010003", "CATERING", null);

        assertThat(mine(user).get("industry").asString())
                .as("餐饮一期接不了，但意向要原样存下来").isEqualTo("CATERING");
    }

    private JsonNode master() throws Exception {
        String body = mvc().perform(get("/common/master-data"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data");
    }

    private static List<String> codes(JsonNode arr) {
        List<String> out = new ArrayList<>();
        if (arr != null) {
            for (JsonNode n : arr) {
                out.add(n.get("industry").asString());
            }
        }
        return out;
    }

    private static JsonNode row(JsonNode arr, String code) {
        for (JsonNode n : arr) {
            if (code.equals(n.get("industry").asString())) {
                return n;
            }
        }
        return null;
    }

    private JsonNode mine(String token) throws Exception {
        String body = mvc().perform(get("/mp/merchant/apply").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data");
    }

    private String submit(String token, String name, String phone, String industry, String note)
            throws Exception {
        /*
         * **先断言这个人名下还没有进行中的单**。不断言的话，号段撞了别的测试类时
         * 下面那句得到的是 10409 CONFLICT —— 一个与「行业口径」毫不相干的错，
         * 而排查的人会先去翻行业那几行代码。让失败自己说出原因，比省一次请求值。
         */
        JsonNode existing = mine(token);
        assertThat(existing == null || existing.isNull())
                .as("手机号 %s 名下已有进行中的入驻单 —— 号段撞了别的测试类"
                        + "（一人只能有一份，见本类头部注释）", phone)
                .isTrue();

        String noteJson = note == null ? "" : ",\"industryNote\":\"" + note + "\"";
        String body = mvc().perform(post("/mp/merchant/apply").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"contactPhone\":\"" + phone + "\","
                                + "\"category\":\"生鲜\",\"industry\":\"" + industry + "\"" + noteJson + "}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("applyNo").asString();
    }
}
