package ai.neargo.shop.scenario;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.community.entity.CmtCommunity;
import ai.neargo.shop.community.mapper.CommunityMappers.CommunityMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import ai.neargo.shop.support.TestLogin;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 「附近」的判据是**每个聚落自己的围栏**，不是一个全局常量。
 *
 * <p>`cmt_community.fence_radius` 这一列早就有了：线上 23 个聚落全设了 1000 米、
 * 运营端能设、ops 列表也显示它 —— <b>而匹配一行都没读</b>，用的是全局
 * {@code shop.community.nearby-radius-m}（默认 5000）。于是「附近」实际是
 * 「中心点五公里内」：小区尺度上还凑合，楼栋尺度上直接不成立
 * （五公里内可以有几十栋写字楼，排第一的大概率不是你所在的那栋）。
 *
 * <p><b>这一条守的是「读没读」，所以判据必须两向都验</b>：
 * 只验「收紧后匹配不到」的话，一个恒返回 false 的实现也能通过。
 */
@SpringBootTest
@ActiveProfiles("test")
class CommunityFenceRadiusTest {

    /** 种子社区 C0001「阳光花园」的坐标（DevSeeder 里写死的） */
    private static final String SEED_NO = "C0001";
    /** 正北约 500 米：0.004492° ≈ 500 m */
    private static final String PROBE_500M = "lat=30.284492&lng=120.100000";

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private CommunityMapper communityMapper;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                        .springSecurity())
                .build();
    }

    private String nearby(String query) throws Exception {
        String body = mvc().perform(get("/mp/community/nearby?" + query))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").toString();
    }

    private void setFence(Integer radius) {
        CmtCommunity patch = new CmtCommunity();
        patch.setFenceRadius(radius);
        DataScopeContext.executeWithoutScope(() -> communityMapper.update(patch,
                Wrappers.<CmtCommunity>lambdaUpdate().eq(CmtCommunity::getCommunityNo, SEED_NO)));
    }

    /**
     * **改完必须还原。** 种子是全量测试共用的 —— 留一个 200 米的围栏在那儿，
     * 别的用例（`CommunityNearbyFlowTest` 站在 30.28 找「阳光花园」）会莫名其妙变红，
     * 而报错与「谁改了围栏」之间没有任何可见联系。
     */
    @AfterEach
    void restoreSeed() {
        // 还原成建表默认值。**不能写 null** —— 这一列是 NOT NULL DEFAULT 1000
        setFence(1000);
    }

    @Test
    @DisplayName("★★★ 围栏收紧到 200 米：500 米外匹配不到；放回 1000 米又能匹配到")
    void matchingReadsEachCommunitysOwnFence() throws Exception {
        setFence(200);
        assertThat(nearby(PROBE_500M))
                .as("围栏 200 米，站在 500 米外还能匹配到 = 匹配根本没读这一列")
                .doesNotContain("阳光花园");

        setFence(1000);
        assertThat(nearby(PROBE_500M))
                .as("放回 1000 米就该匹配得到 —— 只验收紧的话，一个恒 false 的实现也能过")
                .contains("阳光花园");
    }

    @Test
    @DisplayName("★★★ 围栏不许被设成 0 —— 那会让这个聚落谁也匹配不到")
    void fenceCannotBeSetToZero() throws Exception {
        /*
         * 这一列是 NOT NULL DEFAULT 1000，而唯一的写入口 setFence 拒绝 ≤0 ——
         * 两道加起来，「围栏为 0」在库里不存在。
         *
         * **这条守的就是那个前提。** 哪天有人放开了 setFence 的校验，
         * 一个 0 半径的聚落会谁也匹配不到，而它看起来一切正常：
         * 建档成功、列表里有、坐标也对，只是没有任何买家能选到它。
         * 那种缺陷不报错、不崩，只会让一个片区悄悄消失。
         */
        String body = mvc().perform(post("/ops/communities/" + SEED_NO + "/fence")
                        .header("Authorization", "Bearer " + TestLogin.admin(mvc(), json))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"fenceRadius\":0}"))
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(body).path("code").asInt())
                .as("0 被接受了 = 那个聚落从此谁也匹配不到")
                .isNotZero();
    }

    @Test
    @DisplayName("★★ 存量聚落（围栏就是默认的 1000）行为不许因这次改动而变")
    void existingCommunitiesKeepWorking() throws Exception {
        /*
         * 线上 23 个聚落全是默认的 1000 米。这一条防的是
         * 「收紧口径把已开通区域一起挡死」—— 那会是一次没有任何通知的批量下架。
         */
        setFence(1000);
        assertThat(nearby(PROBE_500M))
                .as("默认围栏 1000 米，500 米外必须还在")
                .contains("阳光花园");
    }
}
