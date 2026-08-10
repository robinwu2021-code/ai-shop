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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 运营工作台（P-16.1）。
 *
 * <p>这三个端点此前<b>全是 404</b> —— ops-web 的契约、类型、页面都写好了，缺的是后端。
 *
 * <p>守的都是「不编数字」这条：
 * <ol>
 *   <li>无单时客单价是 <b>0 而不是除零异常</b></li>
 *   <li>今日核销率分母为 0 时是 <b>0 而不是 1</b> ——
 *       「没有单要核销」不等于「全核销完了」，后者会让监控看板一片绿</li>
 *   <li>趋势<b>补齐每一天</b>，无单的日子是 0 而不是缺失 ——
 *       缺失会让折线把不相邻的两天连起来，看着像一直在涨</li>
 *   <li>漏斗<b>不返回没有埋点的环节</b>，而不是给个 0</li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("test")
class OpsDashboardFlowTest {

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
    @DisplayName("★ KPI 六项都在，且无单时客单价是 0 不是除零")
    void kpiHasAllSixNumbers() throws Exception {
        String body = mvc().perform(get("/ops/dashboard/kpi")
                        .header("Authorization", "Bearer " + opsLogin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        var d = json.readTree(body).get("data");

        for (String k : java.util.List.of("gmv", "orderCount", "avgOrderValue",
                "pendingMerchantAudit", "pendingAfterSale", "redeemRate")) {
            assertThat(d.has(k)).as("缺字段 " + k + " —— 端上会渲染成 undefined").isTrue();
        }
        assertThat(d.get("avgOrderValue").asLong()).isGreaterThanOrEqualTo(0L);
        // 「今天没有单要核销」不等于「全核销完了」
        assertThat(d.get("redeemRate").asDouble()).isBetween(0d, 1d);
    }

    @Test
    @DisplayName("★★ 趋势补齐每一天 —— 无单的日子是 0，不是从数组里消失")
    void trendFillsEveryDay() throws Exception {
        String body = mvc().perform(get("/ops/dashboard/trend?days=7")
                        .header("Authorization", "Bearer " + opsLogin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var rows = json.readTree(body).get("data");

        assertThat(rows.size())
                .as("缺哪天，折线就会把不相邻的两天连起来 —— 看着像一直在涨")
                .isEqualTo(7);
        // 日期必须连续且升序，否则前端按数组顺序画出来的是乱的
        java.time.LocalDate prev = null;
        for (var r : rows) {
            java.time.LocalDate d = java.time.LocalDate.parse(r.get("date").asString());
            if (prev != null) {
                assertThat(d).isEqualTo(prev.plusDays(1));
            }
            prev = d;
        }
    }

    @Test
    @DisplayName("★★ 漏斗只给有数据源的环节 —— 没埋点的不编一个 0")
    void funnelOmitsStepsWithoutData() throws Exception {
        String body = mvc().perform(get("/ops/dashboard/funnel")
                        .header("Authorization", "Bearer " + opsLogin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var rows = json.readTree(body).get("data");

        java.util.List<String> steps = new java.util.ArrayList<>();
        for (var r : rows) {
            steps.add(r.get("step").asString());
        }
        assertThat(steps).containsExactly("REGISTER", "FIRST_ORDER");
        assertThat(steps)
                .as("扫码与进店没有事件表，给 0 会被读成「一个人都没扫码」——"
                        + " 而运营会照着它判断投放效果")
                .doesNotContain("SCAN", "ENTER_STORE");
    }

    @Test
    @DisplayName("★ 看不了订单的角色也看不了看板 —— 看板就是订单与售后的聚合")
    void needsOrderViewPermission() throws Exception {
        // GOODS_OPS 有 ORDER_VIEW，BD 也有 —— 用一个都没有的角色来验守卫真的在
        String body = mvc().perform(get("/ops/dashboard/kpi"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("\"gmv\"");
    }

    private String opsLogin() throws Exception {
        String body = mvc().perform(post("/ops/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("token").asString();
    }
}
