package ai.neargo.shop.scenario;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.community.entity.CmtCommunity;
import ai.neargo.shop.community.mapper.CommunityMappers.CommunityMapper;
import ai.neargo.shop.spi.platform.MasterDataPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 「附近」要**先按半径筛、再去富化**。
 *
 * <p><b>为什么判据不能是返回结果。</b> 这次改动**不改变任何输出** ——
 * 改前改后返回的聚落一模一样、顺序也一样。拿结果当判据的话，
 * 把修复整个撤掉它照样绿，那种断言等于没写。
 *
 * <p>2026-09-18 线上实测：`/mp/community/nearby` 要 <b>8.8 秒</b>，
 * 而且**与返回条数无关**（27 条与 6 条同为 8.7s）—— 代价乘在输入上、不在输出上。
 * 原来的顺序是「全读 → 富化 → 过滤」：龙华开城之后 OPEN 聚落有 2783 条，
 * 富化那两句就拿着 2783 个 origin_code 去 62 万行的区划表里反查，
 * 而这些结果 99% 当场被半径筛掉。
 *
 * <p>症状伪装得很像端上的缺陷：选择地点页首屏「附近」整块空着、
 * 「当前位置」只有一行占位文字，看起来是页面没渲染。
 *
 * <p>所以这一条**量的是传进富化那一步的东西**：远处那个聚落的 origin_code
 * 不许出现在 {@code regionNames} 收到的清单里。撤掉修复必然变红。
 *
 * <p><b>两向都验</b>：只验「远的没进去」的话，一个根本不调富化的实现也能过 ——
 * 所以同时断言近的那个**在**清单里。
 */
@SpringBootTest(properties = {
        /*
         * **必须另开一个库。** @MockitoSpyBean 让这个类拿到一个新的 Spring 上下文，
         * 而上下文初始化会把 schema-test.sql 再跑一遍 —— 跑在共用的 `jdbc:h2:mem:shop`
         * 上就是往已经有数据的表里重插种子，整个上下文起不来，
         * 症状与本用例毫无关系（单独跑绿、全量跑红）。与 PayScenePassedThroughTest 同一手法。
         */
        "spring.datasource.url=jdbc:h2:mem:nearby-enrich;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
})
@ActiveProfiles("test")
class NearbyFiltersBeforeEnrichTest {

    /**
     * **合成坐标，离所有种子都远。**
     *
     * <p>探针这一带在种子数据里什么都没有 —— 否则「附近只剩一个」这条断言
     * 会被 DevSeeder 的聚落顶掉，而报错完全不指向真因。
     */
    private static final int NEAR_LAT = 45_000_000;
    private static final int NEAR_LNG = 95_000_000;
    /** 正北约 300 米，落在默认 1000 米围栏内 */
    private static final String PROBE = "lat=45.0027&lng=95.0";

    /** 约 1100 公里外：**同样是 OPEN**，只是远。它的 origin_code 不该被拿去反查 */
    private static final int FAR_LAT = 55_000_000;
    private static final int FAR_LNG = 95_000_000;

    private static final String NEAR_NO = "CT-ENRICH-NEAR";
    private static final String FAR_NO = "CT-ENRICH-FAR";
    /** 合成的 origin_code：**不借真实区划码**，否则全量跑会与别的用例撞主键 */
    private static final String NEAR_ORIGIN = "998801001";
    private static final String FAR_ORIGIN = "998802001";

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private CommunityMapper communityMapper;

    /**
     * 拦住富化那一步看它**收到了哪些 code**。
     *
     * <p>用 spy 不用 mock：mock 会把真实反查整个挡掉，而这条用例要的是
     * 「真实链路跑完之后它被怎么调的」。
     */
    @MockitoSpyBean
    private MasterDataPort masterDataPort;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup
                        .SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    private void seed(String no, String name, int latE6, int lngE6, String originCode) {
        CmtCommunity c = new CmtCommunity();
        c.setCommunityNo(no);
        c.setName(name);
        c.setKind("ESTATE");
        c.setStatus("OPEN");
        c.setLatE6(latE6);
        c.setLngE6(lngE6);
        c.setFenceRadius(1000);
        c.setOriginCode(originCode);
        c.setRegionCode("998800");
        c.setCreatedAt(LocalDateTime.now());
        c.setUpdatedAt(LocalDateTime.now());
        DataScopeContext.executeWithoutScope(() -> communityMapper.insert(c));
    }

    @BeforeEach
    void setUp() {
        seed(NEAR_NO, "近处测试聚落", NEAR_LAT, NEAR_LNG, NEAR_ORIGIN);
        seed(FAR_NO, "远处测试聚落", FAR_LAT, FAR_LNG, FAR_ORIGIN);
    }

    /** 种子是全量测试共用的：自己插的自己收走，别让后面的用例莫名其妙变红 */
    @AfterEach
    void cleanUp() {
        DataScopeContext.executeWithoutScope(() -> communityMapper.delete(
                Wrappers.<CmtCommunity>lambdaQuery()
                        .in(CmtCommunity::getCommunityNo, List.of(NEAR_NO, FAR_NO))));
    }

    @Test
    @DisplayName("★★★ 富化只问活下来的那几条 —— 半径外的 origin_code 不许被拿去反查")
    void enrichmentOnlySeesSurvivors() throws Exception {
        String data = mvc().perform(get("/mp/community/nearby?" + PROBE))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        String body = json.readTree(data).get("data").toString();

        // 对照量先验非零：远的那条**确实在库里、确实是 OPEN**，只是被半径筛掉了。
        // 少了这一句，一个「什么都没插进去」的环境也会让下面两条绿。
        assertThat(body).as("近处那条该在结果里").contains("近处测试聚落");
        assertThat(body).as("远处那条该被半径筛掉").doesNotContain("远处测试聚落");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> codes = ArgumentCaptor.forClass(List.class);
        verify(masterDataPort, atLeastOnce()).regionNames(codes.capture());

        List<String> asked = codes.getAllValues().stream().flatMap(List::stream).toList();
        assertThat(asked)
                .as("半径外的聚落被拿去反查了 = 过滤还在富化后面，代价仍然乘在全库条数上")
                .doesNotContain(FAR_ORIGIN);
        assertThat(asked)
                .as("近的那条也没被问 = 富化根本没跑，上面那条断言是空的")
                .contains(NEAR_ORIGIN);
    }
}
