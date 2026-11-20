package ai.neargo.shop.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ai.neargo.shop.gateway.core.end.Access;
import ai.neargo.shop.gateway.core.resource.ResourceIndex;
import ai.neargo.shop.gateway.core.resource.ResourceIndexes;
import ai.neargo.shop.support.TestLogin;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * C 端接入网关：入口进索引、入库、可以停用；网关不判权限（TDD-网关层与统一权限 §2.25，AC34–AC36）。
 */
@SpringBootTest(properties = "shop.gateway.mp.precheck=observe")
@ActiveProfiles("test")
@DisplayName("网关 · C 端资源索引与停用")
class MpResourceIndexTest {

    private static final String VICTIM = "/mp/category/tree";

    @Autowired WebApplicationContext context;
    @Autowired ResourceIndexes indexes;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mapping;
    MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(SecurityMockMvcConfigurers.springSecurity()).build();
    }

    @Test
    @DisplayName("★★★ AC34：索引是全的；C 端的入口一律记成「留给入口判」，不冒充只需登录或公开")
    void indexCoversEveryConsumerEndpoint() {
        ResourceIndex mp = indexes.of(List.of("/mp"));
        Set<String> registered = new TreeSet<>();
        mapping.getHandlerMethods().keySet().stream()
                .filter(info -> info.getPathPatternsCondition() != null)
                .forEach(info -> info.getPathPatternsCondition().getPatternValues().stream()
                        .filter(p -> p.startsWith("/mp/"))
                        .forEach(p -> {
                            var verbs = info.getMethodsCondition().getMethods();
                            if (verbs.isEmpty()) {
                                registered.add("* " + p);
                            }
                            verbs.forEach(verb -> registered.add(verb.name() + " " + p));
                        }));
        assertThat(mp.all().stream().map(r -> r.method() + " " + r.pattern()).toList())
                .containsExactlyInAnyOrderElementsOf(registered);
        assertThat(mp.all()).isNotEmpty().allSatisfy(r -> assertThat(r.access()).isEqualTo(Access.DEFER));
        // 运营端没有注解的入口仍是「只需登录」，没有被这次改动带偏
        assertThat(indexes.of(List.of("/ops")).locate("POST", "/ops/auth/login").orElseThrow().access())
                .isEqualTo(Access.LOGIN);
    }

    @Test
    @DisplayName("★★ AC35：启动同步，库里 mp 的行与索引条数一致")
    void consumerRowsAreSynced() {
        assertThat(jdbc.queryForObject("select count(*) from sys_api_resource where end_code='mp' and stale=0"
                + " and source='SCAN'", Integer.class)).isEqualTo(indexes.of(List.of("/mp")).all().size());
        assertThat(jdbc.queryForObject("select count(*) from sys_api_resource where end_code='mp'"
                + " and access <> 'DEFER'", Integer.class)).isZero();
    }

    @Test
    @DisplayName("★★★ AC36：没登录也照常访问（网关不判）；在「接口资源」里停用后回 404，恢复后照常；登录入口停不掉")
    void anonymousPassesAndDisableWorks() throws Exception {
        assertThat(mvc.perform(get(VICTIM)).andReturn().getResponse().getStatus()).isEqualTo(200);

        String admin = TestLogin.admin(mvc, json);
        long id = jdbc.queryForObject("select id from sys_api_resource where end_code='mp' and method='GET'"
                + " and pattern=?", Long.class, VICTIM);
        try {
            assertThat(setStatus(admin, id, "DISABLED").path("data").path("status").asString()).isEqualTo("DISABLED");
            assertThat(mvc.perform(get(VICTIM)).andReturn().getResponse().getStatus()).isEqualTo(404);
        } finally {
            setStatus(admin, id, "ENABLED");
        }
        assertThat(mvc.perform(get(VICTIM)).andReturn().getResponse().getStatus()).isEqualTo(200);

        List<Long> logins = jdbc.queryForList("select id from sys_api_resource where end_code='mp'"
                + " and (pattern like '%/login%' or pattern like '%/otp%')", Long.class);
        assertThat(logins).as("C 端应当有登录或验证码入口").isNotEmpty();
        for (Long login : logins) {
            assertThat(setStatus(admin, login, "DISABLED").path("code").asInt()).isEqualTo(10400);
        }
    }

    private JsonNode setStatus(String token, long id, String status) throws Exception {
        return json.readTree(mvc.perform(post("/ops/api-resources/" + id + "/status")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"" + status + "\"}"))
                .andReturn().getResponse().getContentAsString());
    }
}
