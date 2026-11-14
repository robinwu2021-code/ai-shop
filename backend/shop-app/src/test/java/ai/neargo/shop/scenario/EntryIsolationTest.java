package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ai.neargo.shop.support.TestLogin;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

/**
 * 入口互不串（ADR-045 §2-2；开发计划 M1.5）：每个入口只认自己那一种凭证。
 * 运营令牌拿去调开放 API、开放 API 的签名拿去调运营端、运营令牌拿去调商家端 —— 都在守卫这一层 401，不进 controller。
 * 每条都配一个正向对照：同一份凭证在自己的入口上是过得去的，否则 401 可能只是凭证本身坏了。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:pay-i3;MODE=MySQL;"
        + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class EntryIsolationTest {

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;

    private MockMvc mvc;

    @BeforeEach
    void world() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        if (jdbc.queryForObject("SELECT COUNT(*) FROM pay_app WHERE app_no=?", Integer.class, OpenApiFlowTest.APP1) == 0) {
            jdbc.update("INSERT INTO pay_app (app_no, name, owner_type, status, scopes, rate_limit_qps,"
                    + " daily_refund_limit_minor, sandbox, created_at, updated_at) VALUES (?, ?, 'PARTNER', 'ACTIVE', ?,"
                    + " 1000, 5000, 1, NOW(), NOW())", OpenApiFlowTest.APP1, "试点", "pay:create,pay:read,pay:refund");
        }
    }

    /** 签好的开放 API 请求：用对外规则的独立实现签名 */
    private MockHttpServletRequestBuilder signed(String path) throws Exception {
        long ts = System.currentTimeMillis();
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String sig = OpenApiFlowTest.sign(OpenApiFlowTest.SECRET1, "GET", path, Map.of(), OpenApiFlowTest.APP1, ts,
                nonce, "");
        return get(path).header("X-App-Id", OpenApiFlowTest.APP1).header("X-Timestamp", String.valueOf(ts))
                .header("X-Nonce", nonce).header("X-Signature", sig);
    }

    private MockHttpServletRequestBuilder bearer(MockHttpServletRequestBuilder b, String token) {
        return b.header("Authorization", "Bearer " + token);
    }

    private MockHttpServletResponse run(MockHttpServletRequestBuilder b) throws Exception {
        return mvc.perform(b.contentType(MediaType.APPLICATION_JSON).content("")).andReturn().getResponse();
    }

    @Test
    @DisplayName("★★★ 运营令牌调开放 API → 401；同一令牌调运营端过得去")
    void opsTokenDoesNotOpenTheOpenApi() throws Exception {
        String admin = TestLogin.admin(mvc, json);

        assertThat(run(bearer(get("/open/pay/v1/orders/PYO-NOPE"), admin)).getStatus()).isEqualTo(401);
        assertThat(run(bearer(get("/pay-ops/v1/orders"), admin)).getStatus()).as("正向对照").isEqualTo(200);
    }

    @Test
    @DisplayName("★★★ 开放 API 的签名调运营端 / 商家端 → 401；同一份签名调开放 API 过得去")
    void openSignatureDoesNotOpenOpsOrBiz() throws Exception {
        assertThat(run(signed("/pay-ops/v1/orders")).getStatus()).isEqualTo(401);
        assertThat(run(signed("/biz/pay/statement")).getStatus()).isEqualTo(401);
        assertThat(run(signed("/open/pay/v1/orders/PYO-NOPE")).getStatus()).as("正向对照：过了守卫，单不存在是业务结果")
                .isNotEqualTo(401);
    }

    @Test
    @DisplayName("★★★ 运营令牌调商家端 → 401：平台员工不是商家操作员")
    void opsTokenIsNotAMerchant() throws Exception {
        String admin = TestLogin.admin(mvc, json);

        assertThat(run(bearer(get("/biz/pay/statement"), admin)).getStatus()).isEqualTo(401);
        assertThat(run(bearer(post("/biz/pay/collection"), admin)).getStatus()).isEqualTo(401);
    }
}
