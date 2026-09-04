package ai.neargo.shop.scenario;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * `/mp/location/resolve`：一个坐标解析出「我在哪」与归属链。
 *
 * <p><b>为什么要有这个端点</b>：端上此前拿 `nearby` 的第一条当「我在哪」——
 * 那把「最内层怎么定」这条业务规则放进了端，而 c-app / b-app / 将来的 H5
 * 会各写一份，它们迟早不一样。
 *
 * <p>这一批**不改行为**，只搬位置：今天只有 ESTATE/VILLAGE 两档、
 * 层级比较器是空转的，所以 innermost 必须与 `nearby[0]` 一致。
 * 这一条正是「搬位置没搬坏」的判据。
 */
@SpringBootTest
@ActiveProfiles("test")
class LocationResolveTest {

    /** 种子社区 C0001「阳光花园」附近 */
    private static final int LAT_E6 = 30280000;
    private static final int LNG_E6 = 120100000;

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

    private JsonNode data(String url) throws Exception {
        String body = mvc().perform(get(url))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data");
    }

    @Test
    @DisplayName("★★★ 与端上原来取 nearby[0] 的结果一致 —— 这一批只搬位置不改行为")
    void resolveMatchesWhatTheClientUsedToPick() throws Exception {
        JsonNode near = data("/mp/community/nearby?lat=30.28&lng=120.10");
        assertThat(near).as("种子社区必须在附近里，否则这条用例什么都没验到").isNotEmpty();
        String expected = near.get(0).get("communityNo").asString();

        JsonNode ctx = data("/mp/location/resolve?latE6=" + LAT_E6 + "&lngE6=" + LNG_E6);
        assertThat(ctx.get("innermostNo").asString())
                .as("搬到后端之后结果变了 = 这一批改了行为，而它本不该改")
                .isEqualTo(expected);
        assertThat(ctx.get("innermostName").asString())
                .as("顶栏直接显示它，省端上再查一次")
                .isNotBlank();
    }

    @Test
    @DisplayName("★★★ 站在一个社区的**正中心**，它必须排第一 —— 不是排最后")
    void standingExactlyOnACommunityRanksItFirst() throws Exception {
        /*
         * 撞出来的一处真缺陷：`nearby` 的排序原先写的是
         * `distance == 0 ? MAX : distance` —— 用「距离为 0」当「算不出距离」的替身。
         * 而距离恰好为 0 还有另一种含义：**我正站在它上面**。
         * 于是站在小区中心点，它被排到最后，700 米外的邻居小区排第一。
         *
         * 不报错、不空白，只是把最该在第一位的那个放到了最末，而这是 C 端的第一屏。
         */
        JsonNode near = data("/mp/community/nearby?lat=30.28&lng=120.10");
        assertThat(near.get(0).get("distance").asInt())
                .as("站在正中心却不是第一条 = 排序把「距离 0」当成了「算不出距离」")
                .isZero();
    }

    @Test
    @DisplayName("★★★ 归属链含最内层自己 —— 二批加 parent_no 时只填内容，形状不变")
    void chainContainsInnermost() throws Exception {
        JsonNode ctx = data("/mp/location/resolve?latE6=" + LAT_E6 + "&lngE6=" + LNG_E6);
        assertThat(ctx.get("chainNos").toString()).contains(ctx.get("innermostNo").asString());
    }

    @Test
    @DisplayName("★★★ 模糊坐标**不做聚落匹配** —— 5 公里误差配 1000 米围栏，出来的是噪音")
    void coarseCoordsResolveToNothing() throws Exception {
        JsonNode ctx = data("/mp/location/resolve?latE6=" + LAT_E6 + "&lngE6=" + LNG_E6 + "&coarse=true");
        /*
         * 噪音在界面上与真结果**长得一模一样**：顶栏一样显示一个小区名、
         * 商品一样列出来，只是全都不是他那一带的。所以宁可返回空，
         * 让端上降级为「按区给候选列表」。
         */
        assertThat(ctx.get("innermostNo").isNull())
                .as("模糊坐标也返回了 innermost = 把 5 公里误差伪装成了精确匹配")
                .isTrue();
        assertThat(ctx.get("coarse").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("★★ 一个围栏都没落进 → 空，不是异常（新城区就是这个状态）")
    void noFenceHitIsNotAnError() throws Exception {
        JsonNode ctx = data("/mp/location/resolve?latE6=23129000&lngE6=113264000"); // 广州
        assertThat(ctx.get("innermostNo").isNull()).isTrue();
        assertThat(ctx.get("chainNos")).isEmpty();
    }
}
