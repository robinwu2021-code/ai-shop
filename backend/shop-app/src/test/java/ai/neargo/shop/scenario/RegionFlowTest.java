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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 行政区划与社区归属（ADR-013 阶段一）。
 *
 * <p><b>H2 上没有那 44703 行</b> —— 区划数据由 {@code V31__seed_regions.java} 灌入真库，
 * 而测试跑在不走 Flyway 的 H2 上（这正是把数据放进 Java 迁移的目的：
 * 4 万行不该被抄进 {@code schema-test.sql}）。所以这组用例**自己造几行区划**，
 * 验的是逐级查询、路径回溯、社区归属这几条逻辑，不是数据本身。
 */
@SpringBootTest
@ActiveProfiles("test")
class RegionFlowTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private ai.neargo.shop.platform.mapper.PlatformMappers.RegionMapper regionMapper;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                        .springSecurity())
                .build();
    }

    /** 造一条「浙江省 → 杭州市 → 西湖区 → 北山街道」，幂等（重复跑不重复插） */
    private void seedChain() {
        seed("33", null, "PROVINCE", "浙江省");
        seed("3301", "33", "CITY", "杭州市");
        seed("330106", "3301", "DISTRICT", "西湖区");
        seed("330106001", "330106", "STREET", "北山街道");
    }

    private void seed(String code, String parent, String level, String name) {
        var exists = regionMapper.selectCount(com.baomidou.mybatisplus.core.toolkit.Wrappers
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

    @Test
    @DisplayName("★★★ C 端的区划**止于区县**，且不需要登录 —— 那是「我家在哪」不是「我能在哪取货」")
    void consumerRegionsStopAtDistrictAndNeedNoLogin() throws Exception {
        seedChain();

        // **不带 token**：区划是公共参照数据。要登录才查得到的话，
        // 「先填地址、再登录下单」这条路就走不通了
        JsonNode top = data(mvc().perform(get("/mp/regions"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString());
        assertThat(codes(top)).contains("33");

        JsonNode cities = data(mvc().perform(get("/mp/regions").param("parent", "33"))
                .andReturn().getResponse().getContentAsString());
        assertThat(codes(cities)).containsExactly("3301");

        JsonNode districts = data(mvc().perform(get("/mp/regions").param("parent", "3301"))
                .andReturn().getResponse().getContentAsString());
        assertThat(codes(districts)).containsExactly("330106");
        /*
         * **区县的 hasChild 必须压成 false。** 不压的话端上看到还能往下钻，
         * 点进去是「街道」—— 而 usr_address 只有 province/city/district 三列，
         * 街道没有地方放。让人挑一个存不下去的东西，比不让他挑更糟。
         */
        assertThat(districts.get(0).get("hasChild").asBoolean())
                .as("区县不该再显示可下钻 —— 地址表没有街道那一列").isFalse();

        // 街道那一级即便直接问也不给：它属于自提点与经营范围的模型，不是地址簿的
        JsonNode streets = data(mvc().perform(get("/mp/regions").param("parent", "330106"))
                .andReturn().getResponse().getContentAsString());
        assertThat(names(streets))
                .as("北山街道不该出现在地址用的区划里").isEmpty();
    }

    @Test
    @DisplayName("★ 逐级查：省级取顶层（parent 为空），再按 parent 往下走")
    void childrenGoLevelByLevel() throws Exception {
        seedChain();
        String ops = opsLogin();

        // 顶层：parent 不传。注意是 parent_code IS NULL，不是空串 ——
        // 两者并存的话「取顶层」要判两次，漏一处就少半棵树
        JsonNode top = data(mvc().perform(get("/ops/regions").header("Authorization", "Bearer " + ops))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString());
        assertThat(codes(top)).contains("33");

        JsonNode cities = data(mvc().perform(get("/ops/regions").param("parent", "33")
                        .header("Authorization", "Bearer " + ops))
                .andReturn().getResponse().getContentAsString());
        assertThat(codes(cities)).containsExactly("3301");
        // hasChild 让端上知道「还要不要再往下选一层」，而不是点进去才发现是空的
        assertThat(cities.get(0).get("hasChild").asBoolean()).isTrue();

        JsonNode streets = data(mvc().perform(get("/ops/regions").param("parent", "330106")
                        .header("Authorization", "Bearer " + ops))
                .andReturn().getResponse().getContentAsString());
        assertThat(codes(streets)).containsExactly("330106001");
        assertThat(streets.get(0).get("hasChild").asBoolean())
                .as("街道是叶子，不该让人再点进去").isFalse();
    }

    @Test
    @DisplayName("★ 路径回溯从省到自身 —— 端上不该自己按码长切片")
    void pathIsTopDown() throws Exception {
        seedChain();
        String ops = opsLogin();
        JsonNode path = data(mvc().perform(get("/ops/regions/path").param("code", "330106001")
                        .header("Authorization", "Bearer " + ops))
                .andReturn().getResponse().getContentAsString());
        assertThat(names(path)).containsExactly("浙江省", "杭州市", "西湖区", "北山街道");
    }

    @Test
    @DisplayName("查不到的区划码返回空，不抛异常 —— 区划每年调整，存量里会有撤并的旧码")
    void unknownCodeIsEmptyNotError() throws Exception {
        String ops = opsLogin();
        JsonNode path = data(mvc().perform(get("/ops/regions/path").param("code", "999999999")
                        .header("Authorization", "Bearer " + ops))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString());
        assertThat(path).isEmpty();
    }

    @Test
    @DisplayName("★ 给社区设归属，列表回显整条中文路径")
    void communityCarriesRegionPath() throws Exception {
        seedChain();
        String ops = opsLogin();

        mvc().perform(post("/ops/communities/C0001/region")
                        .header("Authorization", "Bearer " + ops)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"regionCode\":\"330106001\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.regionCode").value("330106001"))
                // 路径拼在后端：端上只拿到 330106001 的话，要么显示一串数字，
                // 要么自己按码长切片再逐级查
                .andExpect(jsonPath("$.data.regionPath").value("浙江省 / 杭州市 / 西湖区 / 北山街道"));
    }

    @Test
    @DisplayName("★ 挂到不存在的区划码被拒 —— 否则这个社区在任何按区覆盖里都出不来，且不报错")
    void unknownRegionIsRejected() throws Exception {
        String ops = opsLogin();
        String body = mvc().perform(post("/ops/communities/C0001/region")
                        .header("Authorization", "Bearer " + ops)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"regionCode\":\"888888888\"}"))
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(body).get("code").asInt()).isEqualTo(10404);
    }

    @Test
    @DisplayName("清空归属是允许的 —— 挂错了要能改回来")
    void regionCanBeCleared() throws Exception {
        seedChain();
        String ops = opsLogin();
        mvc().perform(post("/ops/communities/C0001/region")
                .header("Authorization", "Bearer " + ops)
                .contentType(MediaType.APPLICATION_JSON).content("{\"regionCode\":\"330106001\"}"));

        mvc().perform(post("/ops/communities/C0001/region")
                        .header("Authorization", "Bearer " + ops)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"regionCode\":\"\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.regionCode").doesNotExist());
    }

    @Test
    @DisplayName("商品运营改不了区划 —— 那是主数据，与行业、开城同级")
    void goodsOpsCannotBrowseRegions() throws Exception {
        String goods = opsLogin("goods", "goods123");
        mvc().perform(get("/ops/regions").header("Authorization", "Bearer " + goods))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(10403));
    }

    // ---------------------------------------------------------------- helpers

    private JsonNode data(String body) {
        return json.readTree(body).get("data");
    }

    private java.util.List<String> codes(JsonNode rows) {
        var out = new java.util.ArrayList<String>();
        rows.forEach(r -> out.add(r.get("regionCode").asString()));
        return out;
    }

    private java.util.List<String> names(JsonNode rows) {
        var out = new java.util.ArrayList<String>();
        rows.forEach(r -> out.add(r.get("name").asString()));
        return out;
    }

    private String opsLogin() throws Exception {
        return opsLogin("admin", "admin123");
    }

    private String opsLogin(String username, String password) throws Exception {
        String body = mvc().perform(post("/ops/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("token").asString();
    }
}
