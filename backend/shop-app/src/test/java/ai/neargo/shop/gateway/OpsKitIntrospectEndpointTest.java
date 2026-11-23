package ai.neargo.shop.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.TokenStore;
import ai.neargo.shop.support.TestLogin;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** OpsKit 令牌内省（TDD-OpsKit审批对接 AC1，契约 §1）。 */
@SpringBootTest(properties = {"shop.opskit.introspect.client-id=opskit", "shop.opskit.introspect.client-secret=intro-secret"})
@ActiveProfiles("test")
@DisplayName("网关 · OpsKit 令牌内省")
class OpsKitIntrospectEndpointTest {

    private static final String PATH = "/internal/opskit/introspect";
    private static final String BASIC = "Basic " + Base64.getEncoder().encodeToString("opskit:intro-secret".getBytes(StandardCharsets.UTF_8));

    @Autowired
    WebApplicationContext context;
    @Autowired
    ObjectMapper json;
    @Autowired
    TokenStore tokenStore;

    @Test
    @DisplayName("★★★ Basic 凭据不对或没带：401，不看令牌")
    void clientCredentialRequired() throws Exception {
        String admin = TestLogin.admin(mvc(), json);
        assertThat(call(null, admin).getResponse().getStatus()).isEqualTo(401);
        String wrong = "Basic " + Base64.getEncoder().encodeToString("opskit:nope".getBytes(StandardCharsets.UTF_8));
        assertThat(call(wrong, admin).getResponse().getStatus()).isEqualTo(401);
        assertThat(call("Basic !!!not-base64", admin).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("★★★ 没令牌、令牌无效、商户员工令牌（S3 再做）、员工不存在：200 active=false")
    void inactiveTokens() throws Exception {
        assertThat(active(call(BASIC, null))).isFalse();
        assertThat(active(call(BASIC, "otk_nope"))).isFalse();
        String merchant = tokenStore.issue(TokenStore.SessionData.of(LoginUser.merchant("M-ID-1", "m")));
        assertThat(active(call(BASIC, merchant))).isFalse();
        String ghost = tokenStore.issue(TokenStore.SessionData.of(LoginUser.operator("NO-SUCH-STAFF", "x", List.of(), List.of())));
        assertThat(active(call(BASIC, ghost))).isFalse();
    }

    @Test
    @DisplayName("★★★ 有效运营令牌：sub / roles / perms 与 /ops/auth/me 一致，tenant=PLATFORM，超管 scopes=[*]，带 exp")
    void activeOperator() throws Exception {
        String admin = TestLogin.admin(mvc(), json);
        JsonNode me = json.readTree(mvc().perform(get("/ops/auth/me").header("Authorization", "Bearer " + admin))
                .andReturn().getResponse().getContentAsString()).path("data");
        MvcResult r = call(BASIC, admin);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = json.readTree(r.getResponse().getContentAsString());
        assertThat(body.path("active").asBoolean()).isTrue();
        assertThat(body.path("sub")).isEqualTo(me.path("staffNo"));
        assertThat(body.path("roles")).isEqualTo(me.path("roles"));
        assertThat(body.path("perms")).isEqualTo(me.path("perms"));
        assertThat(body.path("tenant").asString()).isEqualTo("PLATFORM");
        assertThat(body.path("scopes").get(0).asString()).isEqualTo("*");
        assertThat(body.path("exp").asLong()).isGreaterThan(System.currentTimeMillis() / 1000);
    }

    private static boolean active(MvcResult r) throws Exception {
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        return new ObjectMapper().readTree(r.getResponse().getContentAsString()).path("active").asBoolean();
    }

    private MvcResult call(String authorization, String token) throws Exception {
        var req = post(PATH).contentType(MediaType.APPLICATION_FORM_URLENCODED);
        if (authorization != null) {
            req = req.header("Authorization", authorization);
        }
        if (token != null) {
            req = req.param("token", token);
        }
        return mvc().perform(req).andReturn();
    }

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context).apply(SecurityMockMvcConfigurers.springSecurity()).build();
    }
}
