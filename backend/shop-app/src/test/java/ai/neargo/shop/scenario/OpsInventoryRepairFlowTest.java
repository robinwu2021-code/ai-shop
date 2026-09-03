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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 手动补投影（M2）与库存存疑打标（M2）。
 *
 * <p>两个都是<b>会留下后果的动作</b>，所以测试压的全是「不该发生什么」：
 *
 * <ol>
 *   <li><b>补投影默认试算。</b>{@code shop.inventory.backfill.dry-run} 的默认值是
 *       一个决定，不该被一个按钮悄悄绕过 —— 不传 {@code apply} 就一条都不该搬。
 *       这一条要是坏了，第一个点它的人就已经写了库。</li>
 *   <li><b>存疑打标只记录，不封店。</b>它走 {@code mch_violation} 的
 *       {@code WARN}，而 {@code recordViolation} 对 {@code SUSPEND} 是有副作用的 ——
 *       动作码写错一个字，「比下架轻」就变成了下架。</li>
 *   <li><b>没有事实的存疑打不出去。</b>这条记录会进信用档案，
 *       而一条没有事实的记录在申诉时站不住。</li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("test")
class OpsInventoryRepairFlowTest {

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
    @DisplayName("★★★ 补投影默认试算 —— 不传 apply 就一条都不搬，第一个点它的人不该已经写了库")
    void repairIsADryRunUnlessApplyIsSaid() throws Exception {
        String token = TestLogin.admin(mvc(), json);

        String body = mvc().perform(post("/ops/inventory/repair-projection")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode r = json.readTree(body).get("data");

        assertThat(r.get("moved").asInt())
                .as("没说 apply 却搬了 %s 条 —— dry-run 的默认值被一个按钮绕过去了",
                        r.get("moved"))
                .isZero();
        // 试算仍要给出「会搬多少」，否则运营点了它也判断不了要不要真搬
        assertThat(r.has("pending")).as("试算不给 pending，那这一步等于什么也没说").isTrue();
        assertThat(r.has("scannedSkus")).isTrue();
    }

    @Test
    @DisplayName("★★★ 存疑打标只记录不封店 —— 动作码写错一个字，「比下架轻」就变成了下架")
    void stockDoubtRecordsWithoutSuspending() throws Exception {
        String token = TestLogin.admin(mvc(), json);
        String entityNo = "M0001";

        String before = merchantStatus(token, entityNo);
        mvc().perform(post("/ops/merchant/" + entityNo + "/stock-doubt")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"detail\":\"连续 3 天账实差 > 20%，且无盘点记录\"}"))
                .andExpect(jsonPath("$.code").value(0));

        assertThat(merchantStatus(token, entityNo))
                .as("打个存疑标就把商家状态改了 —— 这是下架，不是「比下架轻」")
                .isEqualTo(before);
    }

    @Test
    @DisplayName("★★ 没有事实的存疑打不出去 —— 它会进信用档案，商家问「凭什么」要答得上")
    void stockDoubtNeedsAFact() throws Exception {
        mvc().perform(post("/ops/merchant/M0001/stock-doubt")
                        .header("Authorization", "Bearer " + TestLogin.admin(mvc(), json))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"detail\":\"   \"}"))
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not(0)));
    }

    private String merchantStatus(String token, String entityNo) throws Exception {
        String body = mvc().perform(org.springframework.test.web.servlet.request
                        .MockMvcRequestBuilders.get("/ops/merchants/" + entityNo)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("status").asString();
    }
}
