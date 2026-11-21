package ai.neargo.shop.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.TokenStore;
import ai.neargo.shop.auth.TokenStores;
import ai.neargo.shop.gateway.core.resource.ResourceSyncRunner;
import ai.neargo.shop.gateway.iam.resource.ApiResourceAdmin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

/**
 * C 端「要不要登录」在库里配，网关据此拦未登录的请求；业务入口不为此加任何声明
 * （TDD-权限统一到网关层-修复方案 第 5 步，按「规则在库里」的做法）。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:mp-login-in-table;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "shop.gateway.mp.precheck=enforce"})
@ActiveProfiles("test")
@DisplayName("网关 · C 端要不要登录在库里配")
class MpLoginInTableTest {

    @Autowired
    WebApplicationContext web;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ObjectMapper json;
    @Autowired
    TokenStores tokens;
    @Autowired
    ResourceSyncRunner sync;
    @Autowired
    ApiResourceAdmin admin;

    @Test
    @DisplayName("★★★ 把一条公开入口在库里配成要登录：未登录被网关回 401，登录了照常；别的入口不受影响；撤销后恢复")
    void loginRequirementConfiguredInTheTableIsEnforcedByTheGateway() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(web).apply(SecurityMockMvcConfigurers.springSecurity()).build();
        sync.syncOnReady();
        String consumer = tokens.of(Realm.CONSUMER).issue(TokenStore.SessionData.of(LoginUser.consumer("U-MPLOGIN", "买家")));
        long id = jdbc.queryForObject("select id from sys_api_resource where end_code='mp' and method='GET'"
                + " and pattern='/mp/goods'", Long.class);

        // 配之前：这条入口业务上是公开的，匿名能进
        assertThat(mvc.perform(get("/mp/goods")).andReturn().getResponse().getStatus()).isEqualTo(200);

        admin.adjust(id, "LOGIN", null, "tester");
        var anonymous = mvc.perform(get("/mp/goods")).andReturn().getResponse();
        assertThat(anonymous.getStatus()).as("未登录由网关拦下").isEqualTo(401);
        assertThat(json.readTree(anonymous.getContentAsString()).path("code").asInt()).isEqualTo(10401);
        assertThat(mvc.perform(get("/mp/goods").header("Authorization", "Bearer " + consumer))
                .andReturn().getResponse().getStatus()).as("登录了照常").isEqualTo(200);
        // 没配的入口不受影响：仍由业务自己判
        assertThat(mvc.perform(get("/mp/home")).andReturn().getResponse().getStatus()).isNotEqualTo(401);

        admin.adjust(id, null, null, "tester");
        assertThat(mvc.perform(get("/mp/goods")).andReturn().getResponse().getStatus()).as("撤销后恢复").isEqualTo(200);
    }
}
