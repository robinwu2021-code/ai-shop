package ai.neargo.shop.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.PermChecker;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.TokenStore;
import ai.neargo.shop.gateway.biz.BizAccessDecider;
import ai.neargo.shop.gateway.core.end.Access;
import ai.neargo.shop.gateway.core.resource.ApiResource;
import ai.neargo.shop.gateway.core.resource.PermRule;
import ai.neargo.shop.gateway.core.resource.PermRuleParser;
import ai.neargo.shop.spi.merchant.ManagementAuthorityPort;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.ObjectMapper;

/**
 * 管理范围的权限由入口注解与网关按同一套取法判（TDD-管理范围权限搬到网关-权限码方案 第 1 步）。
 *
 * <p>这一步只让网关「认得」新写法：没有任何入口用它，所以线上行为不变。这里验证的是写法、取法与判定本身。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:management-scoped-perm;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "shop.management.signing-key=management-scoped-perm-test-key-at-least-32-chars"})
@ActiveProfiles("test")
@DisplayName("网关 · 管理范围的权限码")
class ManagementScopedPermTest {

    @Autowired
    WebApplicationContext web;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ObjectMapper json;
    @Autowired
    ai.neargo.shop.auth.TokenStores tokens;
    @Autowired
    ManagementAuthorityPort port;
    @Autowired
    PermChecker perm;

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    @DisplayName("★★★ 解析器认得两种写法，且只认精确的形状；运营端的解析不认它们")
    void parserRecognisesTheTwoForms() {
        PermRule on = PermRuleParser.parse("@perm.canManage('sv.brand.activity.read', 'BRAND', #brandNo)",
                PermRuleParser.BIZ_METHODS).orElseThrow();
        assertThat(on.kind()).isEqualTo(PermRule.Kind.SCOPED);
        assertThat(on.code()).isEqualTo("sv.brand.activity.read");
        assertThat(on.scopeType()).isEqualTo("BRAND");
        assertThat(on.scopeParam()).isEqualTo("brandNo");
        assertThat(on).hasToString("sv.brand.activity.read @BRAND(brandNo)");

        PermRule any = PermRuleParser.parse("@perm.canManageAny('sv.settlement.read','ENTITY')",
                PermRuleParser.BIZ_METHODS).orElseThrow();
        assertThat(any.scopeParam()).isNull();
        assertThat(any).hasToString("sv.settlement.read @ENTITY(任一)");

        // 可以和门店角色的码组合
        PermRule mixed = PermRuleParser.parse(
                "@perm.canBiz('biz:customer') and @perm.canManageAny('sv.store.report','STORE')",
                PermRuleParser.BIZ_METHODS).orElseThrow();
        assertThat(mixed.codes()).containsExactly("biz:customer", "sv.store.report");
        assertThat(mixed.hasScoped()).isTrue();
        // 不知道范围时，带范围的码一律算没有
        assertThat(mixed.test(code -> true)).isFalse();
        assertThat(mixed.test(code -> true, node -> true)).isTrue();

        // 形状不对的一律认不出（留给入口判），不猜
        for (String wrong : List.of(
                "@perm.canManage('a','BRAND')",                       // 少了资源参数
                "@perm.canManage('a','BRAND',#body.brandNo())",        // 资源是表达式，不是参数
                "@perm.canManage('a','BRAND','B1')",                   // 资源写死
                "@perm.canManage('a',#type,#brandNo)",                 // 类型不是字面量
                "@perm.canManageAny('a')",
                "@perm.canManageAny('a','ENTITY',#x)")) {
            assertThat(PermRuleParser.parse(wrong, PermRuleParser.BIZ_METHODS)).as(wrong).isEmpty();
        }
        assertThat(PermRuleParser.parse("@perm.canManageAny('a','ENTITY')", PermRuleParser.OPS_METHODS)).isEmpty();
    }

    @Test
    @DisplayName("★★★ 真实的管理空间：属主对空间与品牌有权，外人没有；入口注解与网关判定器结论相同；没带管理空间号时这一层不判")
    void annotationAndGatewayAgreeOnARealSpace() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(web).apply(SecurityMockMvcConfigurers.springSecurity()).build();
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String entity = "ME" + suffix, account = "MA" + suffix, stranger = "MX" + suffix;
        jdbc.update("INSERT INTO mch_entity(entity_no,name,status,market,tenant_no,created_at,updated_at)"
                + " VALUES (?,'范围权限主体','ACTIVE','CN','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", entity);
        for (String[] who : new String[][] {{account, "13912340001", "1"}, {stranger, "13912340002", "0"}}) {
            jdbc.update("INSERT INTO mch_account(mch_account_no,entity_no,login_phone,display_name,is_owner,is_primary,"
                    + "status,tenant_no,created_at,updated_at) VALUES (?,?,?,'成员',?,1,'ACTIVE','MAIN',"
                    + "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", who[0], entity, who[1], Integer.parseInt(who[2]));
        }
        LoginUser owner = LoginUser.merchant(account, "负责人");
        String token = tokens.of(Realm.MERCHANT).issue(TokenStore.SessionData.of(owner));
        String m = json.readTree(mvc.perform(post("/biz/management/spaces").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "scoped-space").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "范围权限", "sourceAccountNo", account))))
                .andReturn().getResponse().getContentAsString()).path("data").path("managementNo").asString();
        String b = json.readTree(mvc.perform(post("/biz/management/brands").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "scoped-brand").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("managementNo", m, "name", "范围品牌", "status", "ACTIVE"))))
                .andReturn().getResponse().getContentAsString()).path("data").path("brandNo").asString();
        assertThat(m).isNotBlank();
        assertThat(b).isNotBlank();

        // 出口：can 与 canAny
        assertThat(port.can("MAIN", "MCH:" + account, m, "BRAND", b, "brand.read")).isTrue();
        assertThat(port.canAny("MAIN", "MCH:" + account, m, "BRAND", "brand.read")).isTrue();
        assertThat(port.canAny("MAIN", "MCH:" + account, m, "SPACE", "management.enter")).isTrue();
        assertThat(port.can("MAIN", "MCH:" + stranger, m, "BRAND", b, "brand.read")).isFalse();
        assertThat(port.canAny("MAIN", "MCH:" + stranger, m, "BRAND", "brand.read")).isFalse();
        assertThat(port.canAny("MAIN", "MCH:" + account, m, "BRAND", "no.such.permission")).isFalse();
        assertThat(port.canAny("MAIN", "MCH:" + account, "NO-SUCH-SPACE", "BRAND", "brand.read")).isFalse();
        // 属主对主体没有天然权限：要靠授权。没有授权时「任一主体」不成立
        assertThat(port.canAny("MAIN", "MCH:" + account, m, "ENTITY", "sv.settlement.read")).isFalse();

        BizAccessDecider decider = new BizAccessDecider(() -> port);
        ApiResource onBrand = resource("/biz/management/brands/{brandNo}/stored-value/activities",
                "@perm.canManage('brand.read','BRAND',#brandNo)");
        ApiResource anyEntity = resource("/biz/management/stored-value/settlement-bills",
                "@perm.canManageAny('sv.settlement.read','ENTITY')");
        String path = "/biz/management/brands/" + b + "/stored-value/activities";

        // 属主：请求头带管理空间号，资源号取自路径变量
        as(owner, request(path, m, null));
        assertThat(decider.decide(owner, onBrand).permitted()).isTrue();
        assertThat(perm.canManage("brand.read", "BRAND", b)).isTrue();
        assertThat(decider.decide(owner, anyEntity).permitted()).isFalse();
        assertThat(decider.decide(owner, anyEntity).missing()).containsExactly("sv.settlement.read");
        assertThat(perm.canManageAny("sv.settlement.read", "ENTITY")).isFalse();

        // 属主：管理空间号放在查询参数里，一样
        as(owner, request(path, null, m));
        assertThat(decider.decide(owner, onBrand).permitted()).isTrue();
        assertThat(perm.canManage("brand.read", "BRAND", b)).isTrue();

        // 外人：同一个请求，网关与注解都拒
        LoginUser other = LoginUser.merchant(stranger, "外人");
        as(other, request(path, m, null));
        assertThat(decider.decide(other, onBrand).permitted()).isFalse();
        assertThat(decider.decide(other, onBrand).missing()).containsExactly("brand.read");
        assertThat(perm.canManage("brand.read", "BRAND", b)).isFalse();

        // 换一个不属于这个空间的品牌号：拒
        as(owner, request("/biz/management/brands/NOT-MINE/stored-value/activities", m, null));
        assertThat(decider.decide(owner, onBrand).permitted()).isFalse();
        assertThat(perm.canManage("brand.read", "BRAND", "NOT-MINE")).isFalse();

        // 过渡规则：请求上没有管理空间号（写接口放在请求体里）—— 这一层不判，两边都放行，留给服务
        as(other, request(path, null, null));
        assertThat(decider.decide(other, onBrand).permitted()).isTrue();
        assertThat(perm.canManage("brand.read", "BRAND", b)).isTrue();

        // 不是商家身份：两边都拒，不管带没带管理空间号
        LoginUser consumer = LoginUser.consumer("U-SCOPED", "买家");
        as(consumer, request(path, m, null));
        assertThat(decider.decide(consumer, onBrand).permitted()).isFalse();
        assertThat(perm.canManage("brand.read", "BRAND", b)).isFalse();
    }

    private static ApiResource resource(String pattern, String expression) {
        return new ApiResource("GET", pattern, ApiResource.LOCAL, Access.POINT,
                PermRuleParser.parse(expression, PermRuleParser.BIZ_METHODS).orElseThrow(), "test");
    }

    private static MockHttpServletRequest request(String path, String header, String param) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        if (header != null) {
            request.addHeader(ManagementAuthorityPort.HEADER, header);
        }
        if (param != null) {
            request.setParameter(ManagementAuthorityPort.PARAM, param);
        }
        return request;
    }

    private static void as(LoginUser user, MockHttpServletRequest request) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
}
