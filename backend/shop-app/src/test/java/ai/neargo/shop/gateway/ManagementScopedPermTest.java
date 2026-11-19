package ai.neargo.shop.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.TokenStore;
import ai.neargo.shop.auth.TokenStores;
import ai.neargo.shop.gateway.biz.ManagementAuthority;
import ai.neargo.shop.gateway.core.end.Access;
import ai.neargo.shop.gateway.core.resource.ApiResource;
import ai.neargo.shop.gateway.core.resource.PermRule;
import ai.neargo.shop.gateway.core.resource.PermRuleText;
import ai.neargo.shop.gateway.core.resource.ResourceIndexes;
import ai.neargo.shop.gateway.core.resource.ResourceSyncRunner;
import ai.neargo.shop.gateway.iam.resource.ApiResourceAdmin;
import ai.neargo.shop.common.BizException;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
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
 * 权限规则以库里为准，网关读库；业务入口不为网关改写法（TDD-管理范围权限搬到网关-权限码方案）。
 *
 * <p>入口注解启动时抄进接口资源表，是初始值；要让一条「只问是不是商家」的入口带上权限码，在库里调，
 * 网关按调整后的判。业务代码一行不动。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:rules-in-db;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "shop.management.signing-key=rules-in-db-test-key-at-least-thirty-two-chars",
        "shop.gateway.biz.precheck=enforce", "shop.gateway.ops.precheck=enforce", "shop.gateway.mp.precheck=observe"})
@ActiveProfiles("test")
@DisplayName("网关 · 权限规则以库里为准")
class ManagementScopedPermTest {

    private static final String ACTIVITIES = "/biz/management/brands/{brandNo}/stored-value/activities";

    @Autowired
    WebApplicationContext web;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ObjectMapper json;
    @Autowired
    TokenStores tokens;
    @Autowired
    ManagementAuthority authority;
    @Autowired
    ResourceIndexes indexes;
    @Autowired
    ResourceSyncRunner sync;
    @Autowired
    ApiResourceAdmin admin;

    @Test
    @DisplayName("★★★ 库里的文字读回来与入口注解上的规则逐条相同 —— 读库判权不会和读注解判权不一样")
    void everyScannedRuleSurvivesTheRoundTripThroughText() {
        int rules = 0;
        for (String prefix : List.of("/ops", "/pay-ops", "/biz", "/mp")) {
            for (ApiResource resource : indexes.scanned(List.of(prefix))) {
                if (resource.rule() != null) {
                    assertThat(PermRuleText.parse(resource.rule().toString()))
                            .as(resource.method() + " " + resource.pattern()).contains(resource.rule());
                    rules++;
                }
            }
        }
        assertThat(rules).isGreaterThan(900);

        // 带范围的码：两种写法
        PermRule on = PermRuleText.parse("sv.brand.activity.read @BRAND(brandNo)").orElseThrow();
        assertThat(on.kind()).isEqualTo(PermRule.Kind.SCOPED);
        assertThat(on.scopeType()).isEqualTo("BRAND");
        assertThat(on.scopeParam()).isEqualTo("brandNo");
        assertThat(PermRuleText.parse("sv.settlement.read @ENTITY(任一)").orElseThrow().scopeParam()).isNull();
        PermRule mixed = PermRuleText.parse("(biz:customer 且 (a.b @STORE(storeNo) 或 c.d @SPACE(任一)))").orElseThrow();
        assertThat(mixed).hasToString("(biz:customer 且 (a.b @STORE(storeNo) 或 c.d @SPACE(任一)))");
        assertThat(mixed.test(code -> true)).as("不知道范围时带范围的码算没有").isFalse();
        // 读不懂的一律不认
        for (String wrong : List.of("", "(a 且 b 或 c)", "(a 且", "a b", "a @brand(x)", "a @BRAND()", "且", "(a))")) {
            assertThat(PermRuleText.parse(wrong)).as(wrong).isEmpty();
        }
    }

    @Test
    @DisplayName("★★★ 没有调整时，网关按库里判用的索引与入口注解扫出来的逐条相同；再同步一遍也不变")
    void indexFromTheTableEqualsTheScanWhenNothingIsAdjusted() {
        sync.syncOnReady();
        indexes.invalidate();
        for (String prefix : List.of("/ops", "/biz", "/mp")) {
            assertThat(describe(indexes.of(List.of(prefix)).all())).as(prefix)
                    .isEqualTo(describe(indexes.scanned(List.of(prefix))));
        }
    }

    @Test
    @DisplayName("★★★ 在库里给一条「只问是不是商家」的入口配上管理空间的码：网关拦住无权的人、放行属主；业务代码没有改；撤销后恢复")
    void adjustingARuleInTheTableChangesWhatTheGatewayEnforces() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(web).apply(SecurityMockMvcConfigurers.springSecurity()).build();
        sync.syncOnReady();
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String entity = "ME" + suffix, account = "MA" + suffix, stranger = "MX" + suffix;
        jdbc.update("INSERT INTO mch_entity(entity_no,name,status,market,tenant_no,created_at,updated_at)"
                + " VALUES (?,'规则在库主体','ACTIVE','CN','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", entity);
        for (String[] who : new String[][] {{account, "13912340011", "1"}, {stranger, "13912340012", "0"}}) {
            jdbc.update("INSERT INTO mch_account(mch_account_no,entity_no,login_phone,display_name,is_owner,is_primary,"
                    + "status,tenant_no,created_at,updated_at) VALUES (?,?,?,'成员',?,1,'ACTIVE','MAIN',"
                    + "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", who[0], entity, who[1], Integer.parseInt(who[2]));
        }
        String owner = tokens.of(Realm.MERCHANT).issue(TokenStore.SessionData.of(LoginUser.merchant(account, "负责人")));
        String other = tokens.of(Realm.MERCHANT).issue(TokenStore.SessionData.of(LoginUser.merchant(stranger, "外人")));
        String m = json.readTree(mvc.perform(post("/biz/management/spaces").header("Authorization", "Bearer " + owner)
                        .header("Idempotency-Key", "rules-space").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "规则在库", "sourceAccountNo", account))))
                .andReturn().getResponse().getContentAsString()).path("data").path("managementNo").asString();
        String b = json.readTree(mvc.perform(post("/biz/management/brands").header("Authorization", "Bearer " + owner)
                        .header("Idempotency-Key", "rules-brand").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("managementNo", m, "name", "规则品牌", "status", "ACTIVE"))))
                .andReturn().getResponse().getContentAsString()).path("data").path("brandNo").asString();
        assertThat(m).isNotBlank();
        assertThat(b).isNotBlank();

        // 接缝本身：属主对品牌有权，外人没有；属主对主体没有天然权限
        assertThat(authority.can("MAIN", "MCH:" + account, m, "BRAND", b, "brand.read")).isTrue();
        assertThat(authority.canAny("MAIN", "MCH:" + account, m, "BRAND", "brand.read")).isTrue();
        assertThat(authority.can("MAIN", "MCH:" + stranger, m, "BRAND", b, "brand.read")).isFalse();
        assertThat(authority.canAny("MAIN", "MCH:" + stranger, m, "BRAND", "brand.read")).isFalse();
        assertThat(authority.canAny("MAIN", "MCH:" + account, m, "ENTITY", "sv.settlement.read")).isFalse();
        assertThat(authority.canAny("MAIN", "MCH:" + account, "NO-SUCH-SPACE", "BRAND", "brand.read")).isFalse();

        long id = jdbc.queryForObject("select id from sys_api_resource where end_code='biz' and method='GET' and pattern=?",
                Long.class, ACTIVITIES);
        String path = "/biz/management/brands/" + b + "/stored-value/activities";

        // 调整之前：入口上只问是不是商家，网关不拦外人
        assertThat(call(mvc, other, path, m).path("code").asInt()).as("调整前网关不拦").isNotEqualTo(10403);

        // 在库里调整。写进去的是规范写法
        var adjusted = admin.adjust(id, "POINT", "  brand.read   @BRAND(brandNo) ", "tester");
        assertThat(adjusted.adjustedAccess()).isEqualTo("POINT");
        assertThat(adjusted.adjustedRule()).isEqualTo("brand.read @BRAND(brandNo)");
        assertThat(adjusted.access()).as("入口注解抄进来的那一份原样留着").isEqualTo("LOGIN");
        ApiResource located = indexes.of(List.of("/biz")).locate("GET", path).orElseThrow();
        assertThat(located.access()).isEqualTo(Access.POINT);
        assertThat(located.rule()).hasToString("brand.read @BRAND(brandNo)");

        // 外人：管理空间号在请求头里也好、在查询参数里也好，都被网关拦下；属主过得了网关
        JsonNode deniedByHeader = mvc(mvc, get(path).header("Authorization", "Bearer " + other).header("X-Management-No", m));
        assertThat(deniedByHeader.path("code").asInt()).isEqualTo(10403);
        assertThat(call(mvc, other, path, m).path("code").asInt()).isEqualTo(10403);
        assertThat(call(mvc, owner, path, m).path("code").asInt()).as("属主过网关").isNotEqualTo(10403);
        // 属主拿另一个不属于这个空间的品牌号：拦
        assertThat(call(mvc, owner, "/biz/management/brands/NOT-MINE/stored-value/activities", m).path("code").asInt())
                .isEqualTo(10403);
        // 过渡规则：请求上没有管理空间号时网关这一条不判，留给业务
        JsonNode noScope = mvc(mvc, get(path).header("Authorization", "Bearer " + other));
        assertThat(noScope.path("code").asInt()).as("没带管理空间号：网关不判").isNotEqualTo(10403);

        // 管理空间自己的入口：空间号在路径里，「空间」这类资源就是当前这个空间
        long recipients = jdbc.queryForObject("select id from sys_api_resource where end_code='biz' and method='GET'"
                + " and pattern='/biz/management/spaces/{managementNo}/recipients'", Long.class);
        admin.adjust(recipients, "POINT",
                "(management.resource.read @SPACE(managementNo) 且 management.grant.manage @SPACE(managementNo))", "tester");
        String recipientsPath = "/biz/management/spaces/" + m + "/recipients";
        assertThat(mvc(mvc, get(recipientsPath).header("Authorization", "Bearer " + other)).path("code").asInt())
                .as("外人：空间号取自路径").isEqualTo(10403);
        assertThat(mvc(mvc, get(recipientsPath).header("Authorization", "Bearer " + owner)).path("code").asInt())
                .as("属主过网关").isNotEqualTo(10403);
        admin.adjust(recipients, null, null, "tester");

        // 再同步一遍（等于重启）：调整还在，注解抄进来的那一份也没被调整覆盖
        sync.syncOnReady();
        Map<String, Object> row = jdbc.queryForMap("select access, rule_text, adjusted_access, adjusted_rule"
                + " from sys_api_resource where id=?", id);
        assertThat(row.get("access")).isEqualTo("LOGIN");
        assertThat(row.get("adjusted_access")).isEqualTo("POINT");
        assertThat(row.get("adjusted_rule")).isEqualTo("brand.read @BRAND(brandNo)");
        assertThat(String.valueOf(row.get("rule_text"))).contains("managementAccess");

        // 撤销：回到入口注解上的规则，网关不再拦外人
        admin.adjust(id, null, null, "tester");
        assertThat(indexes.of(List.of("/biz")).locate("GET", path).orElseThrow().access()).isEqualTo(Access.LOGIN);
        assertThat(call(mvc, other, path, m).path("code").asInt()).isNotEqualTo(10403);
    }

    @Test
    @DisplayName("★★ 调整要过校验：式子读不懂、调成公开、动登录入口，都拒绝，库里不变")
    void adjustmentsAreValidated() {
        sync.syncOnReady();
        long id = jdbc.queryForObject("select id from sys_api_resource where end_code='biz' and method='GET' and pattern=?",
                Long.class, ACTIVITIES);
        for (String[] bad : new String[][] {{"POINT", "(a 且 b 或 c)"}, {"POINT", ""}, {"POINT", null}, {"PUBLIC", null},
                {"WHATEVER", "a"}}) {
            assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> admin.adjust(id, bad[0], bad[1], "tester")))
                    .as(bad[0] + " " + bad[1]).isInstanceOf(BizException.class);
        }
        Long login = jdbc.queryForObject("select min(id) from sys_api_resource where end_code='biz' and source='SCAN'"
                + " and access='LOGIN' and rule_text is null", Long.class);
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> admin.adjust(login, "POINT", "biz:goods", "tester")))
                .as("登录、验证码这类入口不能调").isInstanceOf(BizException.class);
        assertThat(jdbc.queryForObject("select count(*) from sys_api_resource where adjusted_access is not null"
                + " and (id=? or id=?)", Integer.class, id, login)).isZero();
    }

    private static TreeSet<String> describe(List<ApiResource> resources) {
        TreeSet<String> out = new TreeSet<>();
        resources.forEach(r -> out.add(r.method() + " " + r.pattern() + " " + r.access() + " " + r.rule()));
        return out;
    }

    private JsonNode call(MockMvc mvc, String token, String path, String managementNo) throws Exception {
        return mvc(mvc, get(path).header("Authorization", "Bearer " + token).param("managementNo", managementNo));
    }

    private JsonNode mvc(MockMvc mvc, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        String body = mvc.perform(request).andReturn().getResponse().getContentAsString();
        return body.isBlank() ? json.createObjectNode() : json.readTree(body);
    }
}
