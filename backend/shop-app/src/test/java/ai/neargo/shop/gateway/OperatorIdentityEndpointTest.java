package ai.neargo.shop.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.TokenStore;
import ai.neargo.shop.support.TestLogin;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** 统一认证接口（TDD-支付运营端经网关转发与权限下沉 §2.2、§6）。 */
@SpringBootTest(properties = "shop.gateway.ops.identity.caller-token=pay-caller-token")
@ActiveProfiles("test")
@DisplayName("网关 · 统一认证接口")
class OperatorIdentityEndpointTest {

    private static final String PATH = "/internal/auth/v1/operator";

    @Autowired
    WebApplicationContext context;
    @Autowired
    ObjectMapper json;
    @Autowired
    TokenStore tokenStore;

    @Test
    @DisplayName("★★★ 进程凭据不对或没带：403，不看令牌")
    void callerCredentialRequired() throws Exception {
        String admin = TestLogin.admin(mvc(), json);
        assertThat(mvc().perform(get(PATH).header("Authorization", "Bearer " + admin)).andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(mvc().perform(get(PATH).header("Authorization", "Bearer " + admin).header("X-Internal-Token", "wrong"))
                .andReturn().getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("★★★ 没令牌、令牌无效、不是运营端令牌、员工不存在：401")
    void invalidSessions() throws Exception {
        assertThat(call(null).getResponse().getStatus()).isEqualTo(401);
        assertThat(call("Bearer nope").getResponse().getStatus()).isEqualTo(401);
        String merchant = tokenStore.issue(TokenStore.SessionData.of(LoginUser.merchant("M-ID-1", "m")));
        assertThat(call("Bearer " + merchant).getResponse().getStatus()).isEqualTo(401);
        String ghost = tokenStore.issue(TokenStore.SessionData.of(LoginUser.operator("NO-SUCH-STAFF", "x", List.of(), List.of())));
        assertThat(call("Bearer " + ghost).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("★★★ 有效运营令牌：与 /ops/auth/me 逐字段一致（同一个人同一个结论），权限码按前缀筛")
    void sameAnswerAsAuthMe() throws Exception {
        String admin = TestLogin.admin(mvc(), json);
        JsonNode me = json.readTree(mvc().perform(get("/ops/auth/me").header("Authorization", "Bearer " + admin))
                .andReturn().getResponse().getContentAsString()).path("data");
        var result = mvc().perform(get(PATH).header("Authorization", "Bearer " + admin)
                .header("X-Internal-Token", "pay-caller-token").header("X-Auth-Namespace", "pay:")).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("code").asInt()).isZero();
        JsonNode data = body.path("data");
        for (String field : List.of("staffNo", "realName", "status", "roles", "merchantNo", "communityNo", "pickupNo", "mustChangePassword")) {
            assertThat(data.path(field)).as(field).isEqualTo(me.path(field));
        }
        for (JsonNode code : data.path("perms")) {
            assertThat(code.asText()).satisfiesAnyOf(c -> assertThat(c).isEqualTo("*"), c -> assertThat(c).startsWith("pay:"));
        }
    }

    @Test
    @DisplayName("按前缀筛码：保留超管的 *；不带前缀头时全回")
    void namespaceFilter() {
        var perms = List.of("pay:merchant:view", "order:order:read", "*");
        assertThat(ai.neargo.shop.portal.internal.OperatorIdentityEndpoint.filter(perms, "pay:")).containsExactly("pay:merchant:view", "*");
        assertThat(ai.neargo.shop.portal.internal.OperatorIdentityEndpoint.filter(perms, null)).isEqualTo(perms);
    }

    @Test
    @DisplayName("多个前缀逗号分隔（TDD-支付三端经网关透传与权限归属 §13.5）：任一命中即保留，空白忽略")
    void multipleNamespaces() {
        var perms = List.of("mch:pay:qr", "biz:finance", "biz:store:admin", "biz:store", "biz:order:view", "*");
        assertThat(ai.neargo.shop.portal.internal.OperatorIdentityEndpoint.filter(perms, "mch:pay:, biz:finance ,biz:store:admin"))
                .containsExactly("mch:pay:qr", "biz:finance", "biz:store:admin", "*");
        assertThat(ai.neargo.shop.portal.internal.OperatorIdentityEndpoint.filter(perms, " , ")).isEqualTo(perms);
    }

    private org.springframework.test.web.servlet.MvcResult call(String authorization) throws Exception {
        var request = get(PATH).header("X-Internal-Token", "pay-caller-token");
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        return mvc().perform(request).andReturn();
    }

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context).apply(SecurityMockMvcConfigurers.springSecurity()).build();
    }
}
