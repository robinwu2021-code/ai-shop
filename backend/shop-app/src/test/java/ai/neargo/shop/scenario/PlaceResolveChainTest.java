package ai.neargo.shop.scenario;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.community.entity.CmtCommunity;
import ai.neargo.shop.community.entity.GeoPlace;
import ai.neargo.shop.community.mapper.CommunityMappers.CommunityMapper;
import ai.neargo.shop.community.mapper.CommunityMappers.GeoPlaceMapper;
import ai.neargo.shop.community.support.MapBreaker;
import ai.neargo.shop.spi.platform.GeoPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 「我在哪」那条解析链：**先问不花钱的**。
 *
 * <p>五条分支各自有一个用例，而且**判据是 {@code source}**，不是名字 ——
 * 五条分支给出的名字可以完全一样（同一个地方嘛），只有 source 说得出
 * 「这一次是从哪儿拿到的」。拿名字当判据的话，把整条链拆掉只留地图，
 * 五条断言全绿。
 *
 * <p><b>还有一条比结果更要紧的</b>：命中库里那条时**一次外部调用都不许发**。
 * 这才是整张表存在的理由，而它在返回值上完全看不出来 —— 两次请求返回的
 * 东西一模一样，区别只在有没有花掉一次额度。
 */
@SpringBootTest(properties = {
        // 另开一个库：@MockitoBean 会让这个类拿到新的 Spring 上下文，
        // 跑在共用的 jdbc:h2:mem:shop 上会重插种子起不来（与 PayScenePassedThroughTest 同因）
        "spring.datasource.url=jdbc:h2:mem:place-chain;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
})
@ActiveProfiles("test")
class PlaceResolveChainTest {

    /** 合成坐标，离所有种子都远 —— 否则「没落进围栏」这个前提就不成立了 */
    private static final int LAT = 45_000_000;
    private static final int LNG = 95_000_000;
    /** 另一个格子（差约 1 公里），用来验熔断时不再外呼 */
    private static final int LAT2 = 45_010_000;
    private static final int LNG2 = 95_010_000;

    private static final String FENCE_NO = "CT-PLACE-FENCE";

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private CommunityMapper communityMapper;
    @Autowired
    private GeoPlaceMapper placeMapper;
    @Autowired
    private MapBreaker breaker;
    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    /**
     * 用 mock 不用 spy：测试环境没配高德密钥，真实实现的 {@code available()} 恒 false，
     * 整条链会永远停在最后一档 —— 那样五条分支里有四条根本跑不到。
     */
    @MockitoBean
    private GeoPort geoPort;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup
                        .SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    private JsonNode resolve(int latE6, int lngE6) throws Exception {
        String body = mvc().perform(get("/mp/location/resolve?latE6=" + latE6 + "&lngE6=" + lngE6))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data");
    }

    private GeoPort.Reverse poi(String name) {
        return new GeoPort.Reverse(name, "新疆某地某路 1 号", "650100", "某街道",
                "乌鲁木齐市", LAT, LNG, GeoPort.Reverse.KIND_POI);
    }

    @BeforeEach
    void setUp() {
        when(geoPort.available()).thenReturn(true);
        when(geoPort.reverse(anyInt(), anyInt())).thenReturn(Optional.of(poi("龙华区地域馆")));
    }

    @AfterEach
    void cleanUp() {
        /*
         * **geo_place 要物理删。** BaseEntity 带逻辑删除，`delete()` 只是把
         * deleted 置 1 —— 而 geo_key 上的唯一索引仍然被那一行占着。
         * 于是下一个用例往同一个格子插入时撞唯一键，报错与「谁删过」毫无关系。
         * 这也是这条测试当场抓到的那个生产缺陷的同一个面（见 PlaceResolver#upsert）。
         */
        jdbc.execute("DELETE FROM geo_place");
        DataScopeContext.executeWithoutScope(() -> communityMapper.delete(
                Wrappers.<CmtCommunity>lambdaQuery().eq(CmtCommunity::getCommunityNo, FENCE_NO)));
        breaker.recordSuccess();
    }

    @Test
    @DisplayName("★★★ ①围栏命中 → source=COMMUNITY，而且**一次地图都不问**")
    void fenceHitNeverAsksTheMap() throws Exception {
        CmtCommunity c = new CmtCommunity();
        c.setCommunityNo(FENCE_NO);
        c.setName("测试聚落");
        c.setKind("ESTATE");
        c.setStatus("OPEN");
        c.setLatE6(LAT);
        c.setLngE6(LNG);
        c.setFenceRadius(1000);
        c.setRegionCode("650100");
        c.setCreatedAt(LocalDateTime.now());
        c.setUpdatedAt(LocalDateTime.now());
        DataScopeContext.executeWithoutScope(() -> communityMapper.insert(c));

        JsonNode place = resolve(LAT, LNG).get("place");
        assertThat(place.get("source").asString()).isEqualTo("COMMUNITY");
        assertThat(place.get("name").asString()).isEqualTo("测试聚落");
        verify(geoPort, never()).reverse(anyInt(), anyInt());
    }

    @Test
    @DisplayName("★★★ ④没见过的格子问一次地图，②第二次直接命中库 —— 不再外呼")
    void secondCallHitsTheLocalPlaceDb() throws Exception {
        JsonNode first = resolve(LAT, LNG).get("place");
        assertThat(first.get("source").asString()).isEqualTo("MAP");
        assertThat(first.get("kind").asString()).as("取名要取到建筑那一档").isEqualTo("POI");
        assertThat(first.get("name").asString()).isEqualTo("龙华区地域馆");

        JsonNode second = resolve(LAT, LNG).get("place");
        assertThat(second.get("source").asString()).isEqualTo("PLACE_DB");
        assertThat(second.get("name").asString()).isEqualTo("龙华区地域馆");

        /*
         * **这一条才是整张表存在的理由。** 两次请求返回的东西一模一样，
         * 区别只在第二次有没有花掉一次额度 —— 而那在返回值上完全看不出来。
         */
        verify(geoPort, times(1)).reverse(anyInt(), anyInt());
    }

    @Test
    @DisplayName("★★★ ⑤地图不可用 + 库里那条已超期 → 照样给地名，但标 stale")
    void staleRowIsServedAndLabelledWhenMapIsDown() throws Exception {
        resolve(LAT, LNG);                       // 先让它落一行
        DataScopeContext.executeWithoutScope(() -> {
            GeoPlace row = placeMapper.selectList(Wrappers.<GeoPlace>lambdaQuery()).get(0);
            GeoPlace patch = new GeoPlace();
            patch.setId(row.getId());
            patch.setVerifiedAt(LocalDateTime.now().minusDays(40));
            return placeMapper.updateById(patch);
        });
        when(geoPort.available()).thenReturn(false);   // 地图挂了
        reset(geoPort);
        when(geoPort.available()).thenReturn(false);

        JsonNode place = resolve(LAT, LNG).get("place");
        assertThat(place.get("source").asString()).isEqualTo("PLACE_DB_STALE");
        assertThat(place.get("stale").asBoolean()).isTrue();
        assertThat(place.get("name").asString())
                .as("地图挂了就什么都不给的话，这张表白建了")
                .isEqualTo("龙华区地域馆");
        verify(geoPort, never()).reverse(anyInt(), anyInt());
    }

    @Test
    @DisplayName("★★★ ⑥地图不可用且库里没有 → place 为空，**不编地名**")
    void nothingKnownGivesNoPlaceAtAll() throws Exception {
        when(geoPort.available()).thenReturn(false);
        JsonNode data = resolve(LAT2, LNG2);
        assertThat(data.get("place").isNull())
                .as("推不出地名就该留空，端上退回区县 —— 编一个是骗人")
                .isTrue();
        // 对照：区县那一档还在，不是整条链都哑了
        assertThat(data.has("regionCode")).isTrue();
    }

    @Test
    @DisplayName("★★★ 熔断打开之后，一次外部调用都不许发")
    void openBreakerStopsAllOutboundCalls() throws Exception {
        when(geoPort.reverse(anyInt(), anyInt())).thenThrow(new RuntimeException("模拟地图抽风"));
        // 连续失败到阈值
        resolve(LAT, LNG);
        resolve(LAT2, LNG2);
        resolve(LAT + 20_000, LNG + 20_000);
        assertThat(breaker.isOpen()).as("连续失败到阈值应当熔断").isTrue();

        reset(geoPort);
        when(geoPort.available()).thenReturn(true);
        resolve(LAT + 40_000, LNG + 40_000);
        verify(geoPort, never())
                .reverse(anyInt(), anyInt());
    }

    @Test
    @DisplayName("★★ 地图挂了，搜索仍然搜得到本地库里的东西 —— 搜索框不必消失")
    void searchStillWorksWithoutTheMap() throws Exception {
        resolve(LAT, LNG);                       // 让「龙华区地域馆」进库
        when(geoPort.available()).thenReturn(false);

        String body = mvc().perform(get("/mp/place/search?kw=地域馆"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        List<JsonNode> hits = json.readTree(body).get("data").valueStream().toList();
        assertThat(hits).as("地图挂了就一条都不给的话，端上只能把搜索框藏起来").isNotEmpty();
        assertThat(hits.get(0).get("name").asString()).isEqualTo("龙华区地域馆");
        assertThat(hits.get(0).get("source").asString()).isEqualTo("PLACE_DB");
    }
}
