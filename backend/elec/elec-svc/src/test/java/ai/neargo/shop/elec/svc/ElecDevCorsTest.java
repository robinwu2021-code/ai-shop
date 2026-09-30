package ai.neargo.shop.elec.svc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;

/**
 * 开发期跨域：配了 {@code elec.dev-cors-origins} 时<b>只放行列出的源</b>。
 * 反方向（生产不配 = 链上没有 CORS）在 {@link ElecDevCorsOffTest}。
 *
 * <p>两个类而不是一个类里两个 {@code @Nested}：surefire 在本仓库的配置下不跑嵌套类 ——
 * 第一版就是那么写的，报告里 {@code Tests run: 0}，断言一条都没执行。
 */
@SpringBootTest(classes = ElecApplication.class, properties = "elec.dev-cors-origins=" + ElecDevCorsTest.OPS_WEB)
@ActiveProfiles("test")
@Import(FakeMainSystem.Config.class)
class ElecDevCorsTest {

    static final String OPS_WEB = "http://localhost:3100";

    @Autowired
    private WebApplicationContext ctx;

    static MvcResult preflight(WebApplicationContext ctx, String origin) throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(ctx)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
        return mvc.perform(options("/elec/ops/rfq").header("Origin", origin)
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "authorization")).andReturn();
    }

    @Test
    @DisplayName("配了：放行运营端的源，别的源照样拦")
    void allowsListedOriginOnly() throws Exception {
        assertThat(preflight(ctx, OPS_WEB).getResponse().getHeader("Access-Control-Allow-Origin"))
                .isEqualTo(OPS_WEB);
        MvcResult evil = preflight(ctx, "http://evil.example");
        assertThat(evil.getResponse().getHeader("Access-Control-Allow-Origin")).isNull();
        assertThat(evil.getResponse().getStatus()).isEqualTo(403);
    }
}
