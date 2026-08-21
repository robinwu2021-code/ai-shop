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
 * 「附近社区」的距离闸（TDD-定位与未开通区域）。
 *
 * <p>此前<b>没有任何距离过滤</b>：请求带广州坐标，返回的是杭州的「阳光花园」，
 * 距离 1056 公里，却排在附近列表第一位。用户能绑上去，然后下单一件他永远取不到的货。
 *
 * <p><b>不是查不到，是查到了一个错的</b> —— 这种错不报异常、不返回空，
 * 页面看起来完全正常，要等到取货那天才暴露。
 */
@SpringBootTest
@ActiveProfiles("test")
class CommunityNearbyFlowTest {

    /** 种子社区 C0001「阳光花园」在杭州西湖区（DevSeeder 里写死的坐标） */
    private static final String HANGZHOU = "lat=30.28&lng=120.10";
    /** 同城但隔了约 60 公里 —— 仍在杭州，只是不在走得到的范围里 */
    private static final String HANGZHOU_FAR = "lat=30.82&lng=120.10";
    private static final String GUANGZHOU = "lat=23.129&lng=113.264";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ai.neargo.shop.platform.mapper.PlatformMappers.RegionMapper regionMapper;

    @Autowired
    private ObjectMapper json;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                        .springSecurity())
                .build();
    }

    /**
     * 区划的三行祖先。**H2 上没有真库那 44703 行** —— 区划由 `V31__seed_regions.java`
     * 灌进真库，测试库里一行都没有。不补的话 `regionNames` 查不到名字，
     * 区域清单会因为「查不到名字的丢掉」而恒为空，而那看起来像功能没做。
     */
    @org.junit.jupiter.api.BeforeEach
    void seedRegions() {
        seedRegion("33", null, "PROVINCE", "浙江省");
        seedRegion("3301", "33", "CITY", "杭州市");
        seedRegion("330106", "3301", "DISTRICT", "西湖区");
    }

    private void seedRegion(String code, String parent, String level, String name) {
        Long exists = regionMapper.selectCount(com.baomidou.mybatisplus.core.toolkit.Wrappers
                .<ai.neargo.shop.platform.entity.SysRegion>lambdaQuery()
                .eq(ai.neargo.shop.platform.entity.SysRegion::getRegionCode, code));
        if (exists != null && exists > 0) {
            return;
        }
        var r = new ai.neargo.shop.platform.entity.SysRegion();
        r.setRegionCode(code);
        r.setParentCode(parent);
        r.setLevel(level);
        r.setName(name);
        r.setEnabled(true);
        r.setSort(0);
        regionMapper.insert(r);
    }

    private JsonNode nearby(String query) throws Exception {
        String body = mvc().perform(get("/mp/community/nearby?" + query))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data");
    }

    @Test
    @DisplayName("★★★ 同一个社区：站在它旁边能看到，隔一个城市就看不到")
    void farAwayCommunitiesAreNotNearby() throws Exception {
        JsonNode near = nearby(HANGZHOU);
        assertThat(near).isNotEmpty();
        assertThat(near.toString())
                .as("站在杭州西湖区，杭州的社区必须出现 —— 闸修窄了会把已开通区域一起挡死")
                .contains("阳光花园");

        JsonNode far = nearby(GUANGZHOU);
        assertThat(far.toString())
                .as("广州坐标却返回杭州的社区，就是把 1056 公里外的点伪装成「附近」")
                .doesNotContain("阳光花园");
    }

    @Test
    @DisplayName("★★ 同城但超出半径也不算附近 —— 判据是距离，不是行政区")
    void sameCityButOutOfRadiusIsNotNearby() throws Exception {
        assertThat(nearby(HANGZHOU_FAR).toString()).doesNotContain("阳光花园");
    }

    @Test
    @DisplayName("★★ 不传坐标（定位失败/拒绝）→ 不过滤，全部返回")
    void withoutCoordsNothingIsFiltered() throws Exception {
        /*
         * 定位拿不到时没有「近」可言，此时过滤等于把所有人都判成「未开通」。
         * 端上据此显示「未获取定位，以下是全部已开通社区」。
         */
        assertThat(nearby("").toString()).contains("阳光花园");
    }

    @Test
    @DisplayName("★★★ 区域清单只列**有已开通社区**的区，并带上社区数")
    void openRegionsOnlyListsPlacesThatActuallyHaveShops() throws Exception {
        String body = mvc().perform(get("/mp/community/regions"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        JsonNode regions = json.readTree(body).get("data");

        /*
         * 库里有 2978 个区县、41352 个街道。返回的必须是**有社区的那几个**，
         * 而不是整棵区划树 —— 把树扔给用户，他十有八九挑到一个一家店都没有的区，
         * 那不是选区域，那是抽奖。
         */
        assertThat(regions).hasSizeLessThan(50);
        assertThat(regions.toString()).contains("西湖区").contains("杭州市");

        JsonNode xihu = null;
        for (JsonNode r : regions) {
            if ("西湖区".equals(r.get("name").asString())) {
                xihu = r;
            }
        }
        assertThat(xihu).as("演示社区挂在西湖区（V179 补的区划）").isNotNull();
        assertThat(xihu.get("communityCount").asInt())
                .as("「西湖区 · 2 个小区」比光秃秃一个区名有用得多")
                .isGreaterThan(0);
        assertThat(xihu.get("regionCode").asString()).isEqualTo("330106");
        assertThat(xihu.get("cityName").asString()).isEqualTo("杭州市");
    }

    @Test
    @DisplayName("★★ 按区域筛：前缀即层级，市码捞出全市，区码只捞本区")
    void communitiesCanBeFilteredByRegion() throws Exception {
        String inXihu = mvc().perform(get("/mp/community?regionCode=330106"))
                .andReturn().getResponse().getContentAsString();
        assertThat(inXihu).contains("阳光花园");

        // 市码是区码的前缀，所以传市码必须也能捞到 —— 这条正是「不用查子区划」的依据
        String inHangzhou = mvc().perform(get("/mp/community?regionCode=3301"))
                .andReturn().getResponse().getContentAsString();
        assertThat(inHangzhou).contains("阳光花园");

        // 换一个区：一条都不该有
        String elsewhere = mvc().perform(get("/mp/community?regionCode=440106"))
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(elsewhere).get("data"))
                .as("广州天河区没有已开通社区，不能把别处的塞给他")
                .isEmpty();
    }

    @Test
    @DisplayName("★★★ 附近为空不是死路：/mp/community 仍给得出全部已开通社区")
    void allCommunitiesIsTheWayOut() throws Exception {
        assertThat(nearby(GUANGZHOU)).isEmpty();

        String body = mvc().perform(get("/mp/community"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(body).get("data").toString())
                .as("异地下单是真实场景（给父母下单、出差前囤货）——"
                        + "手动选一个远点是用户的知情选择，与系统把远点伪装成附近是两回事")
                .contains("阳光花园");
    }
}
