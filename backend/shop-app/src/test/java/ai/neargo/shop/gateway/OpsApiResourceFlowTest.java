package ai.neargo.shop.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ai.neargo.shop.support.TestLogin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 运营端「接口资源」页的后端（TDD-网关层与统一权限 §2.23，AC26–AC29）。
 *
 * <p>提前判权开到拦截：停用对本进程入口只在这一档与观察档生效。
 */
@SpringBootTest(properties = "shop.gateway.ops.precheck=enforce")
@ActiveProfiles("test")
@DisplayName("网关 · 运营端接口资源页：能看、超管能停用与恢复、关键入口停不掉")
class OpsApiResourceFlowTest {

    /** 拿来停用的那条链接：一个普通的只读入口。 */
    private static final String VICTIM = "/ops/app-versions";

    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(SecurityMockMvcConfigurers.springSecurity()).build();
    }

    @AfterEach
    void restore() throws Exception {
        setStatus(TestLogin.admin(mvc, json), id("GET", VICTIM), "ENABLED");
    }

    @Test
    @DisplayName("★★ AC26：技术运维能看列表与汇总；筛选与分页生效")
    void tech_ops_reads_list_and_summary() throws Exception {
        String techops = TestLogin.operator(mvc, json, "techops", "techops123");

        JsonNode page = read(techops, "/ops/api-resources?endCode=ops&keyword=app-versions&size=5");
        assertThat(page.path("code").asInt()).as(page.toString()).isZero();
        assertThat(page.path("data").path("total").asLong()).isGreaterThanOrEqualTo(2);
        assertThat(page.path("data").path("records")).isNotEmpty()
                .allSatisfy(row -> assertThat(row.path("pattern").asString()).contains("app-versions"));
        JsonNode first = page.path("data").path("records").get(0);
        assertThat(first.path("access").asString()).isEqualTo("POINT");
        assertThat(first.path("locked").asBoolean()).isFalse();

        JsonNode summary = read(techops, "/ops/api-resources/summary");
        assertThat(summary.path("data").path("precheck").asString()).isEqualTo("enforce");
        assertThat(summary.path("data").path("proxy").asBoolean()).isFalse();
        assertThat(summary.path("data").path("total").asLong()).isEqualTo(
                jdbc.queryForObject("select count(*) from sys_api_resource", Long.class));
    }

    @Test
    @DisplayName("★★★ AC27：技术运维改不了；超管能改，返回新状态并留一条高危审计")
    void only_super_admin_changes_status() throws Exception {
        long id = id("GET", VICTIM);
        String techops = TestLogin.operator(mvc, json, "techops", "techops123");
        assertThat(setStatus(techops, id, "DISABLED").path("code").asInt()).isEqualTo(10403);
        assertThat(status(id)).isEqualTo("ENABLED");

        Integer before = audits();
        JsonNode changed = setStatus(TestLogin.admin(mvc, json), id, "DISABLED");
        assertThat(changed.path("code").asInt()).as(changed.toString()).isZero();
        assertThat(changed.path("data").path("status").asString()).isEqualTo("DISABLED");
        assertThat(changed.path("data").path("updatedBy").asString()).isNotBlank();
        assertThat(audits()).isEqualTo(before + 1);

        assertThat(setStatus(TestLogin.admin(mvc, json), id, "PAUSED").path("code").asInt()).isEqualTo(10400);
    }

    @Test
    @DisplayName("★★★ AC28：停用后那条链接回 404，恢复后照常，不用等快照过期")
    void disabling_takes_effect_immediately() throws Exception {
        String admin = TestLogin.admin(mvc, json);
        long id = id("GET", VICTIM);
        assertThat(read(admin, VICTIM).path("code").asInt()).isZero();

        setStatus(admin, id, "DISABLED");
        assertThat(mvc.perform(get(VICTIM).header("Authorization", "Bearer " + admin))
                .andReturn().getResponse().getStatus()).isEqualTo(404);

        setStatus(admin, id, "ENABLED");
        assertThat(read(admin, VICTIM).path("code").asInt()).isZero();
    }

    @Test
    @DisplayName("★★★ AC29：不带权限码的入口与本页自己的接口停不掉，库里状态不变")
    void locked_rows_cannot_be_disabled() throws Exception {
        String admin = TestLogin.admin(mvc, json);
        Long publicRow = jdbc.queryForObject("select min(id) from sys_api_resource where end_code='ops'"
                + " and source='SCAN' and access='LOGIN'", Long.class);
        assertThat(publicRow).as("运营端应当有不带权限码的入口（登录、当前用户）").isNotNull();
        for (long id : new long[] {publicRow, id("POST", "/ops/api-resources/{id}/status"),
                id("GET", "/ops/api-resources")}) {
            assertThat(setStatus(admin, id, "DISABLED").path("code").asInt()).isEqualTo(10400);
            assertThat(status(id)).isEqualTo("ENABLED");
        }
        JsonNode self = read(admin, "/ops/api-resources?keyword=/ops/api-resources");
        assertThat(self.path("data").path("records")).isNotEmpty()
                .allSatisfy(row -> assertThat(row.path("locked").asBoolean()).isTrue());
    }

    private long id(String method, String pattern) {
        return jdbc.queryForObject("select id from sys_api_resource where end_code='ops' and method=? and pattern=?",
                Long.class, method, pattern);
    }

    private String status(long id) {
        return jdbc.queryForObject("select status from sys_api_resource where id=?", String.class, id);
    }

    private Integer audits() {
        return jdbc.queryForObject("select count(*) from sys_audit_log where op_action='API_RESOURCE_DISABLED'",
                Integer.class);
    }

    private JsonNode read(String token, String url) throws Exception {
        return json.readTree(mvc.perform(get(url).header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString());
    }

    private JsonNode setStatus(String token, long id, String status) throws Exception {
        return json.readTree(mvc.perform(post("/ops/api-resources/" + id + "/status")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"" + status + "\"}"))
                .andReturn().getResponse().getContentAsString());
    }
}
