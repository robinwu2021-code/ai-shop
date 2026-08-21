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
    private ObjectMapper json;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                        .springSecurity())
                .build();
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
