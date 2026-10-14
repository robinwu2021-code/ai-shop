package ai.neargo.shop.scenario;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.mapper.TradeMappers.SubOrderMapper;
import ai.neargo.shop.support.TestLogin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 门店经营排行（门店③）。
 *
 * <p>经营看板早就有商家排行，没有门店维度 —— 而多门店商家的货、单、码都挂在门店上：
 * 商家排行会把「一家很好、一家半死」平均成「还行」，那家半死的店永远看不见。
 *
 * <p>两条断言：<b>确实按 GMV 降序</b>（排行不排序就只是一张表），
 * 以及<b>每一行都带商家名</b> —— 一屏上出现两家都在垫底的店，
 * 「它们是同一个老板的」与「是两家不相干的店」该做的事完全不同。
 */
@SpringBootTest
@ActiveProfiles("test")
class OpsStoreRankFlowTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private SubOrderMapper subOrders;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                        .springSecurity())
                .build();
    }

    @Test
    @DisplayName("★★ 按 GMV 降序，且每行带商家名 —— 排行不排序就只是一张表")
    void rankedByGmvAndCarriesMerchant() throws Exception {
        /*
         * **自己种两笔**，金额一大一小。不种的话测试库里没有带门店号的成交单，
         * 这条用例比的是一张空表 —— 第一版就是这样，而我的非空断言把它拦住了。
         *
         * **小额的先种**，顺序与期望相反。第二版是大额先种，结果去掉排序照样绿：
         * 聚合用的是 LinkedHashMap，插入顺序恰好就是正确顺序，
         * 于是「按 GMV 降序」这条断言在不排序的实现上也成立。
         * 造数据要造成**与要验的性质相反**，否则验的是巧合。
         */
        Long small = seed("SRANK-B", 1_000L);
        Long big = seed("SRANK-A", 50_000L);
        try {
        String body = mvc().perform(get("/ops/dashboard/stores?days=90&limit=50")
                        .header("Authorization", "Bearer " + TestLogin.admin(mvc(), json)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode rows = json.readTree(body).get("data");

        assertThat(rows.size())
                .as("一行都没有 —— 测试库里没有带门店号的成交单，这条用例验不到排序")
                .isGreaterThan(0);

        long prev = Long.MAX_VALUE;
        for (JsonNode r : rows) {
            long gmv = r.get("gmv").asLong();
            assertThat(gmv).as("没有按 GMV 降序：%s 排在了 %s 后面", gmv, prev)
                    .isLessThanOrEqualTo(prev);
            prev = gmv;
            assertThat(r.get("storeNo").asString()).isNotBlank();
            assertThat(r.has("merchantName"))
                    .as("不给商家名的话，两家都在垫底的店看不出是不是同一个老板的")
                    .isTrue();
            // 退款率是个比率，不是个计数 —— 越界说明分母取错了
            assertThat(r.get("refundedRate").asDouble()).isBetween(0d, 1d);
        }

        // 我种的那两笔必须按金额分出先后 —— 否则上面那个循环可能只走了别人的数据
        int iBig = indexOf(rows, "SRANK-A");
        int iSmall = indexOf(rows, "SRANK-B");
        assertThat(iBig).as("种的大额单没出现在排行里").isGreaterThanOrEqualTo(0);
        assertThat(iBig).as("¥500 的店排在了 ¥10 的店后面").isLessThan(iSmall);
        } finally {
            // 种子必须还原：留着它，别的用例里的 GMV 会凭空多出 ¥510
            DataScopeContext.executeWithoutScope(() -> {
                subOrders.deleteById(big);
                return subOrders.deleteById(small);
            });
        }
    }

    private static int indexOf(JsonNode rows, String storeNo) {
        for (int i = 0; i < rows.size(); i++) {
            if (storeNo.equals(rows.get(i).get("storeNo").asString())) {
                return i;
            }
        }
        return Integer.MAX_VALUE;
    }

    private Long seed(String storeNo, long payAmount) {
        OrdSubOrder o = new OrdSubOrder();
        o.setSubOrderNo("SO-RANK-" + System.nanoTime());
        o.setOrderNo("O-RANK-" + System.nanoTime());
        o.setUserNo("U-RANK-PROBE");
        o.setEntityNo("M0001");
        o.setStoreNo(storeNo);
        o.setStatus(OrdSubOrder.COMPLETED);
        o.setPayAmount(payAmount);
        o.setDeleted(0);
        DataScopeContext.executeWithoutScope(() -> subOrders.insert(o));
        return o.getId();
    }
}
