package ai.neargo.shop.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.TokenStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * 只开运营端：C 端与 B 端的入口仍被装配（test 配置带着 api 面），但两端的前缀一律 404
 * （TDD-网关层与统一权限 AC2；开发计划 M2 任务 2.6）。
 *
 * <p>这条测试守的是「关掉一端」不等于「撤掉它的认证链」：链撤了而入口还在，请求会绕过认证。
 */
@SpringBootTest(properties = {"shop.gateway.mp.enabled=false", "shop.gateway.biz.enabled=false"})
@ActiveProfiles("test")
@DisplayName("网关 · 关掉的一端前缀下一律 404")
class GatewayEndClosedTest {

    @Autowired
    WebApplicationContext context;
    @Autowired
    TokenStore tokenStore;

    @Test
    @DisplayName("★★★ 被关掉的两端：不带令牌、带本端有效令牌都是 404；运营端照常要求登录")
    void closedEndsAnswerNotFound() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity()).build();
        String consumer = tokenStore.issue(TokenStore.SessionData.of(LoginUser.consumer("CLOSED-C", "closed")));
        String merchant = tokenStore.issue(TokenStore.SessionData.of(LoginUser.merchant("CLOSED-B", "closed")));

        assertThat(status(mvc, "/mp/after-sale", null)).isEqualTo(404);
        assertThat(status(mvc, "/mp/after-sale", consumer)).isEqualTo(404);
        assertThat(status(mvc, "/biz/activities", null)).isEqualTo(404);
        assertThat(status(mvc, "/biz/activities", merchant)).isEqualTo(404);
        // 登录入口也在前缀下，同样被封住
        assertThat(mvc.perform(MockMvcRequestBuilders.post("/biz/auth/login")).andReturn().getResponse().getStatus())
                .isEqualTo(404);

        assertThat(status(mvc, "/ops/admission/policies", null)).as("没关的一端不受影响").isEqualTo(401);
    }

    private static int status(MockMvc mvc, String url, String token) throws Exception {
        var request = MockMvcRequestBuilders.get(url);
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }
}
