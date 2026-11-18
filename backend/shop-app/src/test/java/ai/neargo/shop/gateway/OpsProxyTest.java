package ai.neargo.shop.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.TokenStore;
import ai.neargo.shop.gateway.core.integration.ManifestSeedLoader;
import ai.neargo.shop.gateway.core.resource.ResourceIndexes;
import ai.neargo.shop.gateway.core.resource.ResourceStatusCache;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * 运营端转发库里录入的微服务链接（TDD-网关层与统一权限 AC13、AC18、AC19；开发计划 D2）。
 *
 * <p>下游是测试里起的一个桩服务，记下它收到的每个请求。提前判权是关的：微服务链接不看档位，一律在网关判完。
 */
@SpringBootTest(properties = "shop.gateway.ops.proxy=true")
@ActiveProfiles("test")
@DisplayName("网关 · 运营端转发微服务链接")
class OpsProxyTest {

    private static final Pattern CODE = Pattern.compile("\"code\"\\s*:\\s*(-?\\d+)");
    private static final List<String> RECEIVED = new CopyOnWriteArrayList<>();
    private static final HttpServer STUB = startStub();

    @Autowired
    WebApplicationContext context;
    @Autowired
    TokenStore tokenStore;
    @Autowired
    ManifestSeedLoader seeds;
    @Autowired
    ResourceIndexes indexes;
    @Autowired
    ResourceStatusCache statuses;
    @Autowired
    JdbcTemplate jdbc;

    @DynamicPropertySource
    static void stubAddress(DynamicPropertyRegistry registry) {
        registry.add("shop.services.targets.GWSTUB", () -> "http://127.0.0.1:" + STUB.getAddress().getPort());
        registry.add("shop.services.internal-token", () -> "stub-internal-token");
    }

    private static HttpServer startStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                RECEIVED.add(exchange.getRequestMethod() + " " + exchange.getRequestURI()
                        + " token=" + exchange.getRequestHeaders().getFirst("X-Internal-Token")
                        + " operator=" + exchange.getRequestHeaders().getFirst("X-Gateway-Operator")
                        + " auth=" + exchange.getRequestHeaders().getFirst("Authorization"));
                boolean broken = exchange.getRequestURI().getPath().endsWith("/broken");
                byte[] body = "{\"taskId\":\"T1\",\"state\":\"OPEN\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(broken ? 500 : 200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterAll
    static void stopStub() {
        STUB.stop(0);
    }

    @BeforeEach
    void seedLinks() {
        RECEIVED.clear();
        int inserted = seeds.seed("""
                { "service": "GWSTUB", "end": "ops", "endpoints": [
                  { "method": "GET",  "path": "/ops/x/gwstub/tasks/{taskId}",
                    "target": "/internal/gwstub/v1/tasks/{taskId}" },
                  { "method": "POST", "path": "/ops/x/gwstub/tasks/{taskId}/approve",
                    "target": "/internal/gwstub/v1/tasks/{taskId}/approve", "points": ["gwstub:task:approve"] },
                  { "method": "GET",  "path": "/ops/x/gwstub/tasks/{taskId}/broken",
                    "target": "/internal/gwstub/v1/tasks/{taskId}/broken" },
                  { "method": "POST", "path": "/ops/x/gwstub/no-point", "target": "/internal/gwstub/v1/no-point" },
                  { "method": "GET",  "path": "/biz/x/gwstub/escape", "target": "/internal/gwstub/v1/escape" },
                  { "method": "GET",  "path": "/ops/x/gwstub/open", "target": "/internal/gwstub/v1/open",
                    "access": "PUBLIC" }
                ] }""".getBytes(StandardCharsets.UTF_8));
        assertThat(inserted).as("六条里三条被拒：写操作没带功能点、地址不在本端前缀下、声明了公开").isEqualTo(3);
        indexes.invalidate();
        statuses.invalidate("ops");
    }

    @AfterEach
    void removeLinks() {
        jdbc.update("DELETE FROM sys_api_resource_point WHERE resource_id IN"
                + " (SELECT id FROM sys_api_resource WHERE location = 'GWSTUB')");
        jdbc.update("DELETE FROM sys_api_resource WHERE location = 'GWSTUB'");
        indexes.invalidate();
        statuses.invalidate("ops");
    }

    @Test
    @DisplayName("★★★ 没登录 401、没权限「没有操作权限」，两种情况下游都收不到请求")
    void deniedRequestsNeverReachTheService() throws Exception {
        assertThat(mvc().perform(MockMvcRequestBuilders.get("/ops/x/gwstub/tasks/T1"))
                .andReturn().getResponse().getStatus()).isEqualTo(401);

        var denied = mvc().perform(MockMvcRequestBuilders.post("/ops/x/gwstub/tasks/T1/approve")
                .header("Authorization", "Bearer " + operator())).andReturn().getResponse();
        assertThat(code(denied.getContentAsString())).isEqualTo(10403);

        assertThat(RECEIVED).as("被网关拒掉的请求不该到下游").isEmpty();
    }

    @Test
    @DisplayName("★★★ 判权通过：转到内部地址，带进程凭据与操作人，不带终端令牌；结果放进统一包体")
    void permittedRequestIsForwarded() throws Exception {
        var response = mvc().perform(MockMvcRequestBuilders.get("/ops/x/gwstub/tasks/T-42?verbose=1")
                .header("Authorization", "Bearer " + operator())).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(code(response.getContentAsString())).isZero();
        assertThat(response.getContentAsString()).contains("\"taskId\":\"T1\"").contains("\"state\":\"OPEN\"");

        assertThat(RECEIVED).hasSize(1);
        assertThat(RECEIVED.get(0))
                .startsWith("GET /internal/gwstub/v1/tasks/T-42?verbose=1")
                .contains("token=stub-internal-token").contains("operator=PROXY-O").contains("auth=null");
    }

    @Test
    @DisplayName("★★ 白名单：同一个前缀下没录入的地址到不了下游；被清单规则拒掉的三条也没生效")
    void onlyRecordedLinksAreReachable() throws Exception {
        String token = operator();
        for (String url : List.of("/ops/x/gwstub/unknown", "/ops/x/gwstub/open")) {
            assertThat(mvc().perform(MockMvcRequestBuilders.get(url).header("Authorization", "Bearer " + token))
                    .andReturn().getResponse().getContentAsString()).doesNotContain("\"taskId\"");
        }
        mvc().perform(MockMvcRequestBuilders.post("/ops/x/gwstub/no-point").header("Authorization", "Bearer " + token));
        assertThat(RECEIVED).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_api_resource WHERE location = 'GWSTUB'",
                Integer.class)).isEqualTo(3);
    }

    @Test
    @DisplayName("★★ 下游出错：终端拿到统一的内部错误，看不到下游的原始响应")
    void downstreamFailureIsNotLeaked() throws Exception {
        var response = mvc().perform(MockMvcRequestBuilders.get("/ops/x/gwstub/tasks/T1/broken")
                .header("Authorization", "Bearer " + operator())).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(502);
        assertThat(code(response.getContentAsString())).isEqualTo(10500);
        assertThat(response.getContentAsString()).doesNotContain("taskId");
    }

    @Test
    @DisplayName("★★ 种子不覆盖调整过的值；在库里停用后回 404 且不转发")
    void seedDoesNotOverwriteAndDisableBlocks() throws Exception {
        jdbc.update("UPDATE sys_api_resource SET status = 'DISABLED', updated_by = 'tester'"
                + " WHERE location = 'GWSTUB' AND pattern = '/ops/x/gwstub/tasks/{taskId}'");
        assertThat(seeds.seed("""
                { "service": "GWSTUB", "end": "ops", "endpoints": [
                  { "method": "GET", "path": "/ops/x/gwstub/tasks/{taskId}",
                    "target": "/internal/gwstub/v1/tasks/{taskId}" } ] }""".getBytes(StandardCharsets.UTF_8)))
                .as("这一行已经在了，再写种子不动它").isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM sys_api_resource WHERE location = 'GWSTUB'"
                + " AND pattern = '/ops/x/gwstub/tasks/{taskId}'", String.class)).isEqualTo("DISABLED");

        indexes.invalidate();
        statuses.invalidate("ops");
        assertThat(mvc().perform(MockMvcRequestBuilders.get("/ops/x/gwstub/tasks/T1")
                .header("Authorization", "Bearer " + operator())).andReturn().getResponse().getStatus())
                .isEqualTo(404);
        assertThat(RECEIVED).isEmpty();
    }

    private String operator() {
        return tokenStore.issue(TokenStore.SessionData.of(
                LoginUser.operator("PROXY-O", "proxy", List.of(), List.of())));
    }

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context).apply(SecurityMockMvcConfigurers.springSecurity()).build();
    }

    private static int code(String body) {
        Matcher m = CODE.matcher(body);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }
}
