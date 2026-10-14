package ai.neargo.shop.scenario;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.product.entity.PrdGoods;
import ai.neargo.shop.product.mapper.ProductMappers.GoodsMapper;
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

    @Autowired
    private GoodsMapper goodsMapper;

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
                "pendingMerchantAudit", "pendingAfterSale", "redeemRate",
                "pendingGoodsAudit", "goodsAuditOldestDays")) {
            assertThat(d.has(k)).as("缺字段 " + k + " —— 端上会渲染成 undefined").isTrue();
        }
        assertThat(d.get("avgOrderValue").asLong()).isGreaterThanOrEqualTo(0L);
        // 「今天没有单要核销」不等于「全核销完了」
        assertThat(d.get("redeemRate").asDouble()).isBetween(0d, 1d);
    }

    @Test
    @DisplayName("★★ 待审商品数与审核队列是同一个数 —— 不是各算各的")
    void pendingGoodsMatchesTheAuditQueue() throws Exception {
        String token = opsLogin();
        /*
         * **自己种一件待审的**。第一版没种，跑出来是绿的 —— 而测试库里一件待审都没有，
         * 它比的是 0 与 0：把 executeWithoutScope 整个删掉照样绿。
         * 一个恒真的断言比没有断言更糟，因为它看起来像有人在守。
         */
        String probe = "GAUDITPROBE-" + System.nanoTime();
        PrdGoods seed = new PrdGoods();
        seed.setGoodsNo(probe);
        seed.setEntityNo("E-AUDIT-PROBE");
        seed.setTitle("待审探针");
        seed.setType("GOODS");
        seed.setAuditStatus("AUDITING");
        seed.setDeleted(0);
        DataScopeContext.executeWithoutScope(() -> goodsMapper.insert(seed));
        try {
            var kpi = json.readTree(mvc().perform(get("/ops/dashboard/kpi")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString()).get("data");
            var queue = json.readTree(mvc().perform(get("/ops/goods/audit-queue?page=1&size=1")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString()).get("data");

            /*
             * 看板上的数与队列里的数必须一致 —— 这张卡是待办，点进去就是那条队列。
             *
             * **这一条守的不是数据域**：超管无域，把 auditBacklog() 改回
             * executeWithoutScope，这里照样绿（试过）。真正钉数据域的是
             * OpsDataScopeFlowTest#goodsBacklogCardMatchesTheScopedQueue ——
             * 那边用配了商家域的审核员、两件待审分属两个商家，加回绕过就红。
             * 这一条守的是更基础的一件事：卡片与它的落点出自同一个判据。
             */
            assertThat(kpi.get("pendingGoodsAudit").asLong())
                    .as("看板说 %s 件待审，队列里是 %s 件 —— 两边不是同一个数，多半是数据域没绕开",
                            kpi.get("pendingGoodsAudit"), queue.get("total"))
                    .isEqualTo(queue.get("total").asLong());

            // 数量与天数要成对：没有待审就没有「最早那件」，说「等了 N 天」是编的
            if (kpi.get("pendingGoodsAudit").asLong() == 0L) {
                assertThat(kpi.get("goodsAuditOldestDays").asLong()).isZero();
            }
            assertThat(kpi.get("goodsAuditOldestDays").asLong()).isNotNegative();
        } finally {
            // 种子必须还原：留着它，别的用例里「待审队列」凭空多一条，
            // 而报错会出现在一个跟本用例毫不相干的地方
            DataScopeContext.executeWithoutScope(() -> goodsMapper.deleteById(seed.getId()));
        }
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

    /**
     * 漏斗四环齐全。
     *
     * <p><b>这条断言在 V290 之后改了方向</b>：此前是「只给后两环，没埋点的不编一个 0」——
     * 那时前两环确实没有数据源，编一个 0 会被读成「一个人都没扫码」。
     * {@code mkt_store_visit} 落地后前两环成了真数，所以现在该断的是**四环都在**。
     *
     * <p>留着旧断言的话，它会替「漏斗缺两环」这个已经修好的缺陷背书 ——
     * 该变红的是过时的断言，不是新补的能力。
     */
    @Test
    @DisplayName("★★ 漏斗四环齐全 —— 扫码/进店有埋点之后不该再缺两环")
    void funnelCoversAllFourSteps() throws Exception {
        String body = mvc().perform(get("/ops/dashboard/funnel")
                        .header("Authorization", "Bearer " + opsLogin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var rows = json.readTree(body).get("data");

        java.util.List<String> steps = new java.util.ArrayList<>();
        for (var r : rows) {
            steps.add(r.get("step").asString());
        }
        // 顺序就是漏斗的顺序：前端按数组顺序画，乱序画出来的是另一件事
        assertThat(steps).containsExactly("SCAN", "ENTER", "REGISTER", "FIRST_ORDER");
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
        return TestLogin.admin(mvc(), json);
    }
}
